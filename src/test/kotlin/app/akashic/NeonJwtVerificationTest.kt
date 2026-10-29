package app.akashic

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class NeonJwtVerificationTest {
    private val issuer = "https://example.neonauth.ap-southeast-1.aws.neon.tech/akashic/auth"
    private val settings = mapOf(
        "APP_ENV" to "production",
        "AUTH_MODE" to "oidc",
        "HOST" to "0.0.0.0",
        "JWT_ISSUER" to issuer,
        "JWT_AUDIENCE" to "ai-trpg-client",
        "JWT_JWKS_URL" to issuer + "/.well-known/jwks.json"
    )
    private val time = Instant.ofEpochSecond(1_800_000_000L)
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val keyId = "fixture-key-1"

    private fun jwks() : String {
        val pub = pair.public as EdECPublicKey
        val point = pub.point
        val yBigEndian = point.y.toByteArray()
        val raw = ByteArray(32)
        for (i in 0 until 32) raw[i] = yBigEndian.getOrNull(yBigEndian.size - 1 - i) ?: 0
        if (point.isXOdd) raw[31] = (raw[31].toInt() or 0x80).toByte()
        return Wire.encode(mapOf("keys" to listOf(mapOf(
            "kid" to keyId, "kty" to "OKP", "crv" to "Ed25519",
            "alg" to "EdDSA", "x" to b64.encodeToString(raw)
        ))))
    }

    private fun token(overrides: Map<String, Any> = emptyMap(), algorithm: String = "EdDSA"): String {
        val header = mapOf("alg" to algorithm, "typ" to "JWT", "kid" to keyId)
        val claims = mutableMapOf<String, Any>(
            "iss" to issuer, "aud" to "ai-trpg-client", "sub" to "neon-user-1",
            "iat" to time.epochSecond - 30, "exp" to time.epochSecond + 600
        )
        claims.putAll(overrides)
        val message = b64.encodeToString(Wire.encode(header).toByteArray()) + "." +
            b64.encodeToString(Wire.encode(claims).toByteArray())
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(message.toByteArray(Charsets.US_ASCII))
        return message + "." + b64.encodeToString(signer.sign())
    }

    private fun verifier() = ActorAuth(settings, { jwks() }, { time })
    private fun rejected(token: String) =
        assertEquals("UNAUTHENTICATED",
            assertFailsWith<EngineException> { verifier().actor("Bearer " + token) }.code)

    @Test fun verifiesRealEd25519SignatureAndStableActor() {
        val user = verifier().actor("Bearer " + token())
        assertEquals("u-" + Wire.sha256(issuer + "\u0000" + "neon-user-1").take(32), user)
        assertNotEquals(user, verifier().actor("Bearer " + token(mapOf("sub" to "neon-user-2"))))
    }

    @Test fun rejectsWrongAudienceIssuerAndExpired() {
        rejected(token(mapOf("aud" to "another-app")))
        rejected(token(mapOf("iss" to "https://attacker.example")))
        rejected(token(mapOf("exp" to time.epochSecond - 50)))
        rejected(token(mapOf("iat" to time.epochSecond + 100)))
    }

    @Test fun rejectsNonEdDsaOrTamperedToken() {
        rejected(token(algorithm = "RS256"))
        val good = token()
        val parts = good.split(".")
        val mutated = parts[1].replaceFirst("e", "f")
        rejected(parts[0] + "." + mutated + "." + parts[2])
    }

    @Test fun lockedModeCannotBeBypassed() {
        val auth = ActorAuth(settings + ("AUTH_MODE" to "locked"), { jwks() }, { time })
        assertEquals(503, assertFailsWith<EngineException> {
            auth.actor("Bearer " + token())
        }.status)
    }

    @Test fun disallowArbitraryJwksHost() {
        assertFailsWith<IllegalArgumentException> {
            ActorAuth(settings + ("JWT_JWKS_URL" to "https://attacker.invalid/jwks.json"),
                { jwks() }, { time })
        }
    }
}
