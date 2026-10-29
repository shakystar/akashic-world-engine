package app.akashic

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Explicit Neon Auth JWT verifier.
 * Neon Auth Managed Better Auth uses EdDSA (Ed25519), not RS256.
 * No client OAuth tokens or unverified claims can grant world ownership.
 */
class ActorAuth(
    private val env: Map<String, String>,
    private val jwksLoader: (() -> String)? = null,
    private val clock: () -> Instant = { Instant.now() }
) {
    private val mode = env["AUTH_MODE"] ?: "disabled"
    private val issuer = env["JWT_ISSUER"] ?: ""
    private val audience = env["JWT_AUDIENCE"] ?: ""
    private val jwksUrl = env["JWT_JWKS_URL"] ?: ""
    private val decoder = Base64.getUrlDecoder()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private var cachedKeys: JsonNode? = null
    private var cacheUntil: Instant = Instant.EPOCH

    init {
        require(mode in setOf("dev", "oidc", "locked")) { "Unrecognized AUTH_MODE" }
        if (mode == "dev") {
            require(env["APP_ENV"] == "development") { "Dev bearer cannot run outside development" }
            require((env["HOST"] ?: "127.0.0.1") in setOf("127.0.0.1", "::1", "localhost")) {
                "Dev bearer must bind to loopback only"
            }
        }
        if (mode == "oidc") {
            require(issuer.startsWith("https://") && audience.isNotBlank() &&
                jwksUrl.startsWith("https://")) { "OIDC requires HTTPS issuer, audience and JWKS" }
            val issuerHost = URI(issuer).host ?: error("Malformed JWT_ISSUER")
            val jwksHost = URI(jwksUrl).host ?: error("Malformed JWT_JWKS_URL")
            require(issuerHost.endsWith(".neonauth.ap-southeast-1.aws.neon.tech") &&
                issuerHost == jwksHost && jwksUrl.endsWith("/.well-known/jwks.json")) {
                "Only the intended Neon Auth domain and JWKS path are accepted"
            }
        }
    }

    fun actor(authorization: String?): String {
        if (mode == "locked") throw EngineException(503, "AUTH_NOT_CONFIGURED", "Game API not available")
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length > 8192)
            throw invalid()
        val token = authorization.removePrefix("Bearer ")
        if (mode == "dev") {
            val match = Regex("^dev:([A-Za-z0-9][A-Za-z0-9._:-]{0,127})$").matchEntire(token)
                ?: throw invalid()
            return match.groupValues[1]
        }
        try {
            val parts = token.split(".")
            if (parts.size != 3 || parts.any { it.length !in 1..8192 }) throw invalid()
            val header = jsonPart(parts[0])
            val claims = jsonPart(parts[1])
            if (header.path("alg").asText() != "EdDSA") throw invalid()
            val kid = header.path("kid").asText().takeIf { it.isNotBlank() && it.length <= 256 }
                ?: throw invalid()
            if (header.has("crit") || header.has("jku") || header.has("jwk") || header.has("x5u"))
                throw invalid()
            val jwk = lookupKey(kid)
            if (jwk.path("kty").asText() != "OKP" || jwk.path("crv").asText() != "Ed25519")
                throw invalid()
            if (jwk.has("alg") && jwk.path("alg").asText() != "EdDSA") throw invalid()
            val publicKeyBytes = decode(jwk.path("x").asText())
            if (publicKeyBytes.size != 32) throw invalid()
            val key = makeEd25519Key(publicKeyBytes)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update((parts[0] + "." + parts[1]).toByteArray(StandardCharsets.US_ASCII))
            if (!verifier.verify(decode(parts[2]))) throw invalid()

            val now = clock().epochSecond
            if (claims.path("iss").asText() != issuer) throw invalid()
            val aud = claims.get("aud")
            val matchesAudience = (aud?.isTextual == true && aud.asText() == audience) ||
                (aud?.isArray == true && aud.any { it.isTextual && it.asText() == audience })
            if (!matchesAudience) throw invalid()
            val exp = claims.get("exp")?.takeIf { it.isIntegralNumber }?.asLong() ?: throw invalid()
            val iat = claims.get("iat")?.takeIf { it.isIntegralNumber }?.asLong() ?: throw invalid()
            val nbf = claims.get("nbf")?.takeIf { it.isIntegralNumber }?.asLong()
            if (exp <= now - 30 || iat > now + 30 || (nbf != null && nbf > now + 30) ||
                exp <= iat || exp - iat > 3600L) throw invalid()
            val subject = claims.path("sub").asText()
                .takeIf { it.isNotBlank() && it.length <= 512 } ?: throw invalid()
            return "u-" + Wire.sha256(issuer + "\u0000" + subject).take(32)
        } catch (_: Exception) {
            throw invalid()
        }
    }

    private fun invalid() = EngineException(401, "UNAUTHENTICATED", "Invalid bearer token")

    private fun decode(part: String): ByteArray = decoder.decode(part)
    private fun jsonPart(encoded: String): JsonNode =
        Wire.json.readTree(decode(encoded)).also { if (!it.isObject) throw invalid() }

    private fun makeEd25519Key(x: ByteArray): java.security.PublicKey {
        val bytes = x.clone()
        val odd = (bytes[31].toInt() and 0x80) != 0
        bytes[31] = (bytes[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, bytes.reversedArray())
        return KeyFactory.getInstance("Ed25519").generatePublic(
            EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(odd, y)))
    }

    @Synchronized
    private fun lookupKey(kid: String): JsonNode {
        val now = clock()
        if (cachedKeys == null || !now.isBefore(cacheUntil)) refresh()
        val first = findKey(kid)
        if (first != null) return first
        // Unknown key IDs can indicate legitimate key rotation. Refresh once, then fail closed.
        refresh()
        return findKey(kid) ?: throw invalid()
    }

    private fun findKey(kid: String): JsonNode? =
        cachedKeys?.get("keys")?.takeIf { it.isArray }?.firstOrNull {
            it.path("kid").asText() == kid && it.path("kty").asText() == "OKP" &&
                it.path("crv").asText() == "Ed25519"
        }

    private fun refresh() {
        val body = jwksLoader?.invoke() ?: run {
            val request = HttpRequest.newBuilder(URI(jwksUrl)).GET()
                .timeout(Duration.ofSeconds(5)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200 || response.body().length > 65536)
                throw invalid()
            response.body()
        }
        if (body.length > 65536) throw invalid()
        val doc = Wire.json.readTree(body)
        if (!doc.isObject || doc.get("keys")?.isArray != true) throw invalid()
        cachedKeys = doc
        cacheUntil = clock().plusSeconds(300)
    }
}
