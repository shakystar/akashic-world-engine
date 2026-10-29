package app.akashic

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.jackson.jackson
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.URL
import java.security.interfaces.RSAPublicKey

/** User's ChatGPT OAuth token is never used as the World Engine bearer token. */
class ActorAuth(private val env: Map<String, String>) {
    private val mode = env["AUTH_MODE"] ?: "disabled"
    private val issuer = env["JWT_ISSUER"] ?: ""
    private val audience = env["JWT_AUDIENCE"] ?: ""
    private val jwksUrl = env["JWT_JWKS_URL"] ?: ""
    private val jwks by lazy { JwkProviderBuilder(URL(jwksUrl)).build() }

    init {
        require(mode in setOf("dev", "oidc")) { "AUTH_MODE must be dev or oidc; disabled by default" }
        if (mode == "dev") {
            require(env["APP_ENV"] == "development") { "Dev bearer cannot run outside development" }
            require((env["HOST"] ?: "127.0.0.1") in setOf("127.0.0.1", "::1", "localhost")) {
                "Dev bearer must bind to loopback only"
            }
        } else {
            require(issuer.startsWith("https://") && audience.isNotBlank() && jwksUrl.startsWith("https://")) {
                "OIDC requires HTTPS issuer, HTTPS JWKS, and explicit audience"
            }
        }
    }

    fun actor(authorization: String?): String {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length > 8192) {
            throw EngineException(401,"UNAUTHENTICATED","Bearer token required")
        }
        val token = authorization.removePrefix("Bearer ")
        if (mode == "dev") {
            val match = Regex("^dev:([A-Za-z0-9][A-Za-z0-9._:-]{0,127})$").matchEntire(token)
                ?: throw EngineException(401,"UNAUTHENTICATED","Invalid development bearer")
            return match.groupValues[1]
        }
        try {
            val decoded = JWT.decode(token)
            if (decoded.algorithm != "RS256" || decoded.keyId.isNullOrBlank()) {
                throw EngineException(401,"UNAUTHENTICATED","Unsupported token")
            }
            val key = jwks.get(decoded.keyId).publicKey as? RSAPublicKey
                ?: throw EngineException(401,"UNAUTHENTICATED","Unsupported signing key")
            val verified = JWT.require(Algorithm.RSA256(key, null))
                .withIssuer(issuer).withAudience(audience).acceptLeeway(30).build().verify(token)
            if (verified.expiresAt == null || verified.issuedAt == null) {
                throw EngineException(401,"UNAUTHENTICATED","Missing required token lifetime claims")
            }
            val subject = verified.subject?.takeIf { it.isNotBlank() }
                ?: throw EngineException(401,"UNAUTHENTICATED","Invalid subject")
            // Hash external IDs so actor IDs fit the frozen contract and cannot reveal OAuth PII.
            return "u-" + Wire.sha256("$issuer\u0000$subject").take(32)
        } catch (e: EngineException) {
            throw e
        } catch (_: Exception) {
            throw EngineException(401,"UNAUTHENTICATED","Invalid identity token")
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.jsonBody(): JsonNode {
    val text = receiveText()
    if (text.toByteArray(Charsets.UTF_8).size > 65536) {
        throw EngineException(413,"BAD_REQUEST","JSON body too large")
    }
    val parsed = try { Wire.json.readTree(text) }
        catch (_: Exception) { null }
    if (parsed == null || !parsed.isObject) {
        throw EngineException(400,"BAD_REQUEST","Invalid JSON object")
    }
    return parsed
}

private suspend fun io.ktor.server.application.ApplicationCall.safelyAuthenticated(
    block: suspend (String) -> Unit, auth: ActorAuth,
) {
    try {
        response.header(HttpHeaders.CacheControl, "no-store")
        block(auth.actor(request.headers[HttpHeaders.Authorization]))
    } catch (e: EngineException) {
        respond(HttpStatusCode.fromValue(e.status),e.body())
    } catch (_: Exception) {
        // Never reveal internal SQL, OAuth verification, or hidden NPC facts.
        respond(HttpStatusCode.InternalServerError,
            EngineException(500,"INTERNAL_ERROR","Internal server error").body())
    }
}

fun Application.akashicModule(store: PostgresStore, auth: ActorAuth) {
    install(ContentNegotiation) { jackson() }
    routing {
        get("/health") {
            call.respond(mapOf("ok" to true, "service" to "akashic-world-engine"))
        }
        get("/v1/me") {
            call.safelyAuthenticated({ actor -> call.respond(mapOf("actor_id" to actor)) },auth)
        }
        post("/v1/worlds") {
            call.safelyAuthenticated({ actor ->
                call.respond(HttpStatusCode.Created,store.createWorld(actor,call.jsonBody()))
            },auth)
        }
        route("/v1/worlds/{worldId}") {
            get("/view") {
                call.safelyAuthenticated({ actor ->
                    val id = call.parameters["worldId"]
                        ?: throw EngineException(400,"BAD_REQUEST","Missing worldId")
                    call.respond(store.view(id,actor))
                },auth)
            }
            get("/events") {
                call.safelyAuthenticated({ actor ->
                    val id = call.parameters["worldId"]
                        ?: throw EngineException(400,"BAD_REQUEST","Missing worldId")
                    val keys = call.request.queryParameters.names()
                    if (keys.any { it != "cursor" } ||
                        (call.request.queryParameters.getAll("cursor")?.size ?: 0) > 1) {
                        throw EngineException(400,"BAD_REQUEST","Invalid event cursor")
                    }
                    call.respond(store.events(id,actor,call.request.queryParameters["cursor"]))
                },auth)
            }
            post("/commands") {
                call.safelyAuthenticated({ actor ->
                    val id = call.parameters["worldId"]
                        ?: throw EngineException(400,"BAD_REQUEST","Missing worldId")
                    val result = store.command(id,actor,call.jsonBody())
                    val status = if (result["status"] == "accepted") HttpStatusCode.OK
                        else HttpStatusCode.UnprocessableEntity
                    call.respond(status,result)
                },auth)
            }
            post("/time/advance") {
                call.safelyAuthenticated({ _ ->
                    throw EngineException(422,"TIME_ADVANCE_DENIED",
                        "Only character.rest may fast-forward in this fixture")
                },auth)
            }
        }
    }
}
