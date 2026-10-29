package app.akashic

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

fun Application.akashicModule(store: PostgresStore?, auth: ActorAuth) {
    fun requireStore(): PostgresStore = store
        ?: throw EngineException(503, "AUTH_NOT_CONFIGURED", "Game API is not yet available")
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
                call.respond(HttpStatusCode.Created,requireStore().createWorld(actor,call.jsonBody()))
            },auth)
        }
        route("/v1/worlds/{worldId}") {
            get("/view") {
                call.safelyAuthenticated({ actor ->
                    val id = call.parameters["worldId"]
                        ?: throw EngineException(400,"BAD_REQUEST","Missing worldId")
                    call.respond(requireStore().view(id,actor))
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
                    call.respond(requireStore().events(id,actor,call.request.queryParameters["cursor"]))
                },auth)
            }
            post("/narration") {
                call.safelyAuthenticated({ actor ->
                    val id = call.parameters["worldId"]
                        ?: throw EngineException(400, "BAD_REQUEST", "Missing worldId")
                    // Authorization and visibility checks precede any billable LLM request.
                    val view = requireStore().view(id, actor)
                    val body = call.jsonBody()
                    Wire.objectFields(body, setOf("text"), setOf("text"), "narration request")
                    val userText = Wire.text(body.get("text"), 1200, "text")
                    call.respond(NarrationService().reply(actor, id, view, userText))
                }, auth)
            }
            post("/commands") {
                call.safelyAuthenticated({ actor ->
                    val id = call.parameters["worldId"]
                        ?: throw EngineException(400,"BAD_REQUEST","Missing worldId")
                    val result = requireStore().command(id,actor,call.jsonBody())
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
