package app.akashic

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.fasterxml.jackson.databind.JsonNode
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * HTTP + signed Ed25519 JWT + PostgreSQL end-to-end fixture.
 * These identities are ephemeral CI fixtures, NOT accounts or tokens issued by Neon Auth.
 */
class AuthenticatedWorldFlowTest {
    private val issuedAt = Instant.ofEpochSecond(1_800_000_000L)
    private val base = "https://fixture.neonauth.ap-southeast-1.aws.neon.tech/akashic/auth"
    private val kid = "test-key-1"
    private val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun encode(value: Any): String = b64.encodeToString(Wire.encode(value).toByteArray(Charsets.UTF_8))
    private fun jwks(): String {
        val pub = signer.public as EdECPublicKey
        val p = pub.point
        val big = p.y.toByteArray()
        val little = ByteArray(32)
        for (i in 0..31) little[i] = big.getOrNull(big.size - 1 - i) ?: 0
        if (p.isXOdd) little[31] = (little[31].toInt() or 0x80).toByte()
        return Wire.encode(mapOf("keys" to listOf(mapOf(
            "kty" to "OKP", "crv" to "Ed25519", "kid" to kid,
            "alg" to "EdDSA", "x" to b64.encodeToString(little)
        ))))
    }
    private fun signed(subject: String, audience: String = "world-test"): String {
        val unsigned = encode(mapOf("alg" to "EdDSA", "kid" to kid, "typ" to "JWT")) + "." +
            encode(mapOf("iss" to base, "sub" to subject, "aud" to audience,
                "iat" to issuedAt.epochSecond - 10, "exp" to issuedAt.epochSecond + 600))
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(signer.private)
        sig.update(unsigned.toByteArray(Charsets.US_ASCII))
        return unsigned + "." + b64.encodeToString(sig.sign())
    }
    private fun authenticated() = ActorAuth(mapOf(
        "AUTH_MODE" to "oidc",
        "APP_ENV" to "production",
        "HOST" to "0.0.0.0",
        "JWT_ISSUER" to base,
        "JWT_JWKS_URL" to base + "/.well-known/jwks.json",
        "JWT_AUDIENCE" to "world-test"
    ), { jwks() }, { issuedAt })

    @Test fun authenticatedWorldLifecycleOverHttp() {
        val databaseUrl = System.getenv("TEST_DATABASE_URL")
            ?: error("TEST_DATABASE_URL is required")
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = databaseUrl
            username = System.getenv("TEST_DATABASE_USER") ?: "akashic"
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "test-only"
            maximumPoolSize = 4
        })
        ds.use {
            migrate(ds)
            val store = PostgresStore(ds)
            val tokenA = signed("player-a")
            val tokenB = signed("player-b")
            val actorA = authenticated().actor("Bearer " + tokenA)
            testApplication {
                application { akashicModule(store, authenticated()) }
                suspend fun create(token: String) = client.post("/v1/worlds") {
                    header(HttpHeaders.Authorization, "Bearer " + token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"canon_version_id":"fixture-v1"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") {
                    header(HttpHeaders.Authorization, "Bearer " + signed("player-a", "wrong-audience"))
                }.status)

                val aCreated = create(tokenA)
                assertEquals(HttpStatusCode.Created, aCreated.status)
                val aWorld = Wire.json.readTree(aCreated.bodyAsText())
                val id = aWorld.get("world_id").asText()
                var revision = aWorld.get("view_revision").asText()
                assertEquals(HttpStatusCode.NotFound, client.get("/v1/worlds/" + id + "/view") {
                    header(HttpHeaders.Authorization, "Bearer " + tokenB)
                }.status)

                suspend fun action(commandId: String, type: String, payload: String, rev: String): JsonNode {
                    val request = Wire.json.createObjectNode()
                    request.put("command_id", commandId)
                    request.put("actor_id", actorA)
                    request.put("expected_view_revision", rev)
                    request.put("type", type)
                    request.set<JsonNode>("payload", Wire.json.readTree(payload))
                    val response = client.post("/v1/worlds/" + id + "/commands") {
                        header(HttpHeaders.Authorization, "Bearer " + tokenA)
                        contentType(ContentType.Application.Json)
                        setBody(Wire.encode(request))
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    return Wire.json.readTree(response.bodyAsText())
                }
                val speak = action("speak-1", "dialogue.speak",
                    """{"target_id":"npc-keeper","utterance":"Hello"}""", revision)
                assertEquals(60L, speak.path("world_time").asLong())
                revision = speak.path("view_revision").asText()
                val move = action("move-1", "character.move",
                    """{"destination_location_id":"archive"}""", revision)
                assertEquals(180L, move.path("world_time").asLong())
                revision = move.path("view_revision").asText()
                val rest = action("rest-1", "character.rest",
                    """{"requested_seconds":3600}""", revision)
                assertEquals(300L, rest.path("world_time").asLong())
                assertTrue(rest.path("interrupted").asBoolean())
                assertTrue(store.replayConsistent(id, actorA))

                val viewResponse = client.get("/v1/worlds/" + id + "/view") {
                    header(HttpHeaders.Authorization, "Bearer " + tokenA)
                }
                assertEquals(HttpStatusCode.OK, viewResponse.status)
                val view = viewResponse.bodyAsText()
                assertFalse(view.contains("FIXTURE_SECRET"))
                val eventsResponse = client.get("/v1/worlds/" + id + "/events") {
                    header(HttpHeaders.Authorization, "Bearer " + tokenA)
                }
                assertEquals(HttpStatusCode.OK, eventsResponse.status)
                val visible = eventsResponse.bodyAsText()
                assertFalse(visible.contains("FIXTURE_SECRET"))
                assertFalse(visible.contains("hidden_knowledge"))
                assertEquals(HttpStatusCode.NotFound, client.get("/v1/worlds/" + id + "/events") {
                    header(HttpHeaders.Authorization, "Bearer " + tokenB)
                }.status)
            }
        }
    }
}
