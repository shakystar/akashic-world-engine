package app.akashic

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WorldIntegrationTest {
    private fun withStore(block: (PostgresStore, HikariDataSource) -> Unit) {
        val url = System.getenv("TEST_DATABASE_URL") ?: error("TEST_DATABASE_URL is required for integration tests")
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("TEST_DATABASE_USER") ?: "akashic"
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "test-only"
            maximumPoolSize = 6
        }
        HikariDataSource(config).use { ds -> migrate(ds); block(PostgresStore(ds), ds) }
    }

    @Test fun createWorldAndPersistTurn() = withStore { store, ds ->
        val request = Wire.json.readTree("""{"canon_version_id":"fixture-v1"}""")
        val worldId = store.createWorld("player-a", request)["world_id"].toString()
        val view = store.view(worldId, "player-a")
        val cmd = Wire.json.createObjectNode()
        cmd.put("command_id", "cmd-one")
        cmd.put("actor_id", "player-a")
        cmd.put("expected_view_revision", view["view_revision"].toString())
        cmd.put("type", "dialogue.speak")
        cmd.putObject("payload").apply {
            put("target_id", "npc-keeper")
            put("utterance", "Hello")
        }
        val result = store.command(worldId, "player-a", cmd)
        assertEquals("accepted", result["status"])
        assertEquals(60L, result["world_time"])
        assertEquals(result, store.command(worldId, "player-a", cmd))
        assertTrue(store.replayConsistent(worldId, "player-a"))
        assertTrue(PostgresStore(ds).replayConsistent(worldId, "player-a"))
        assertEquals("WORLD_NOT_FOUND",
            assertFailsWith<EngineException> { store.view(worldId, "player-b") }.code)
    }

    @Test fun restStopsAtScheduledEvent() = withStore { store, _ ->
        val worldId = store.createWorld("player-a",
            Wire.json.readTree("""{"canon_version_id":"fixture-v1"}"""))["world_id"].toString()
        val cmd = Wire.json.createObjectNode()
        cmd.put("command_id", "rest-one")
        cmd.put("actor_id", "player-a")
        cmd.put("expected_view_revision", store.view(worldId, "player-a")["view_revision"].toString())
        cmd.put("type", "character.rest")
        cmd.putObject("payload").put("requested_seconds", 7200L)
        val result = store.command(worldId, "player-a", cmd)
        assertEquals(300L, result["world_time"])
        assertEquals(true, result["interrupted"])
        assertTrue(store.replayConsistent(worldId, "player-a"))
        val publicEvents = Wire.encode(store.events(worldId, "player-a", null))
        assertTrue(!publicEvents.contains(FixtureRules.PRIVATE_NPC_BELIEF))
    }

    @Test fun authRejectsUnsafeConfiguration() {
        assertFailsWith<IllegalArgumentException> {
            ActorAuth(mapOf("APP_ENV" to "production", "AUTH_MODE" to "dev", "HOST" to "0.0.0.0"))
        }
        assertFailsWith<IllegalArgumentException> {
            ActorAuth(mapOf("APP_ENV" to "production", "AUTH_MODE" to "oidc", "HOST" to "0.0.0.0"))
        }
    }
}
