package app.akashic

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val env = System.getenv()
    val auth = ActorAuth(env) // fail closed before listening
    val host = env["HOST"] ?: "127.0.0.1"
    val port = env["PORT"]?.toIntOrNull() ?: 8765
    require(port in 1..65535) { "Invalid PORT" }
    // Read-only connectivity test. Does not expose secrets or unlock the game.
    if (env["AUTH_MODE"] == "locked" && env["DB_PREFLIGHT"] == "true") {
        if (listOf("DATABASE_URL","DATABASE_USER","DATABASE_PASSWORD").any { env[it].isNullOrBlank() }) {
            System.err.println("DB_PREFLIGHT status=MISSING_CONFIGURATION")
        } else {
            try {
                dataSourceFromEnv(env).use { pool ->
                    pool.connection.use { conn ->
                        conn.prepareStatement("SELECT current_database()").use { stmt ->
                            stmt.executeQuery().use { rs ->
                                if (rs.next() && rs.getString(1) == "akashic")
                                    println("DB_PREFLIGHT status=CONNECTED")
                                else System.err.println("DB_PREFLIGHT status=WRONG_DATABASE")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("DB_PREFLIGHT status=FAILED type=" + e.javaClass.simpleName)
            }
        }
    }
    // Explicit, one-off Flyway bootstrap. Must use Neon DIRECT host, never pooled.
    if (env["AUTH_MODE"] == "locked" && env["DB_BOOTSTRAP_MIGRATE"] == "true") {
        val directUrl = env["DATABASE_MIGRATION_URL"]
        require(directUrl != null && directUrl.startsWith("jdbc:postgresql://") &&
            !directUrl.substringBefore("/akashic").contains("-pooler") &&
            directUrl.substringBefore("?").endsWith("/akashic") &&
            directUrl.contains("sslmode=require")) { "Direct TLS migration URL required" }
        try {
            dataSourceFromEnv(env + ("DATABASE_URL" to directUrl)).use { pool -> migrate(pool) }
            println("DB_BOOTSTRAP_MIGRATE status=SUCCESS")
        } catch (e: Exception) {
            // No exception message or stack trace; JDBC and Flyway may contain secrets.
            System.err.println("DB_BOOTSTRAP_MIGRATE status=FAILED type=" + e.javaClass.simpleName)
            throw IllegalStateException("Migration failed; refusing application startup")
        }
    }
    // Locked bootstrap: expose health only; never require DB credentials before auth is ready.
    val store = if (env["AUTH_MODE"] == "locked") null else {
        val pool = dataSourceFromEnv(env)
        Runtime.getRuntime().addShutdownHook(Thread { pool.close() })
        // Never run Flyway against a pooled runtime connection. Migration has
        // its own explicit direct-connection locked bootstrap path.
        pool.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM public.flyway_schema_history WHERE version='1' AND success=TRUE"
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    require(rs.next() && rs.getInt(1) == 1) { "World schema migration V1 is missing" }
                }
            }
        }
        PostgresStore(pool)
    }
    embeddedServer(Netty,host=host,port=port) { akashicModule(store,auth) }.start(wait=true)
}
