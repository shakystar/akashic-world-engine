package app.akashic

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val env = System.getenv()
    val auth = ActorAuth(env) // fail closed before listening
    val host = env["HOST"] ?: "127.0.0.1"
    val port = env["PORT"]?.toIntOrNull() ?: 8765
    require(port in 1..65535) { "Invalid PORT" }
    // Locked bootstrap: expose health only; never require DB credentials before auth is ready.
    val store = if (env["AUTH_MODE"] == "locked") null else {
        val pool = dataSourceFromEnv(env)
        Runtime.getRuntime().addShutdownHook(Thread { pool.close() })
        migrate(pool)
        PostgresStore(pool)
    }
    embeddedServer(Netty,host=host,port=port) { akashicModule(store,auth) }.start(wait=true)
}
