package app.akashic

/** Dependency-free deterministic domain core. No DB, network, clock or LLM calls. */

data class WorldState(
    val id: String,
    val ownerId: String,
    val canonVersionId: String,
    val ruleVersion: String,
    val worldTime: Long = 0,
    val locationId: String = "square",
    val clockMode: String = "interaction",
    val viewRevision: String,
    val lastSeq: Long = 0,
)

sealed interface GameAction {
    data class Speak(val targetId: String, val utterance: String) : GameAction
    data class Move(val destinationLocationId: String) : GameAction
    data class Inspect(val targetId: String) : GameAction
    data class Rest(val requestedSeconds: Long) : GameAction
}

data class ScheduledAction(
    val id: String,
    val dueAt: Long,
    val priority: Int,
    val insertionSeq: Long,
    val kind: String,
)

data class DomainMutation(
    val type: String,
    val at: Long,
    val payload: Map<String, Any>,
    val actorId: String? = null,
    val targetIds: List<String> = emptyList(),
)

data class PublicEmission(
    val at: Long,
    val kind: String,
    val payload: Map<String, Any>,
)

data class TurnPlan(
    val status: String,
    val nextTime: Long,
    val nextLocation: String,
    val elapsed: Long,
    val interrupted: Boolean,
    val events: List<DomainMutation>,
    val emissions: List<PublicEmission>,
    val executedScheduleIds: List<String>,
    val reason: String? = null,
)

class RuleProblem(val code: String, message: String) : RuntimeException(message)

object FixtureRules {
    const val CANON_VERSION = "fixture-v1" // NOT real approved lore.
    const val RULE_VERSION = "fixture-rules-1"
    const val PRIVATE_NPC_BELIEF = "FIXTURE_SECRET:sealed-crown"

    val npcReplies = mapOf(
        "npc-keeper" to "아직 특별히 들은 소식은 없습니다.",
        "npc-herald" to "확인된 소식만 전해 드리겠습니다.",
    )

    /** Only material already visible to the viewer belongs in this view. */
    fun entities(location: String): List<Map<String, Any>> {
        fun entity(id: String, kind: String, name: String, place: String) =
            mapOf("id" to id, "kind" to kind, "display_name" to name,
                "visible_state" to mapOf("location_id" to place))
        val player = entity("player", "person", "여행자", location)
        return if (location == "square") {
            listOf(player, entity("npc-keeper", "person", "여관주인", "square"),
                entity("npc-herald", "person", "전령", "square"),
                entity("bell", "object", "광장의 종", "square"))
        } else {
            listOf(player, entity("shelves", "object", "서고의 책장", "archive"))
        }
    }

    val initialSchedule = listOf(
        ScheduledAction("bell", 300L, 10, 1L, "bell.rings"),
        ScheduledAction("secret", 600L, 20, 2L, "secret.recalled"),
    )
}

/**
 * The model is intentionally small: 2 places, 2 NPCs, 4 commands.
 * More elaborate NPC policies are separate DecisionStrategy implementations.
 */
class TurnEngine {
    fun plan(state: WorldState, actor: String, action: GameAction,
             scheduled: List<ScheduledAction>): TurnPlan {
        val reason = when (action) {
            is GameAction.Speak -> if (state.locationId != "square" ||
                action.targetId !in FixtureRules.npcReplies) "TARGET_NOT_VISIBLE" else null
            is GameAction.Move -> if (action.destinationLocationId !in setOf("square", "archive") ||
                action.destinationLocationId == state.locationId) "INVALID_DESTINATION" else null
            is GameAction.Inspect -> if (action.targetId !in
                FixtureRules.entities(state.locationId).map { it["id"] }) "TARGET_NOT_VISIBLE" else null
            is GameAction.Rest -> if (action.requestedSeconds !in 1L..604800L)
                "INVALID_REST_DURATION" else null
        }
        if (reason != null) return TurnPlan("rejected", state.worldTime, state.locationId,
            0, false, emptyList(), emptyList(), emptyList(), reason)

        val duration = when (action) {
            is GameAction.Speak -> 60L
            is GameAction.Move -> 120L
            is GameAction.Inspect -> 20L
            is GameAction.Rest -> action.requestedSeconds
        }
        val requestedTarget = try { Math.addExact(state.worldTime, duration) }
            catch (_: ArithmeticException) { throw RuleProblem("RESOURCE_LIMIT", "World time overflow") }
        val agenda = scheduled.asSequence()
            .filter { it.dueAt > state.worldTime && it.dueAt <= requestedTarget }
            .sortedWith(compareBy<ScheduledAction> { it.dueAt }
                .thenBy { it.priority }.thenBy { it.insertionSeq }.thenBy { it.id })
            .toList()
        if (agenda.size > 100) throw RuleProblem("RESOURCE_LIMIT", "Too many scheduled events")
        val changes = mutableListOf<DomainMutation>()
        val public = mutableListOf<PublicEmission>()
        val executed = mutableListOf<String>()
        var end = requestedTarget
        var interrupted = false
        for (item in agenda) {
            when (item.kind) {
                "bell.rings" -> {
                    changes += DomainMutation("fixture.bell_rang", item.dueAt,
                        mapOf("location_id" to "square"), null, listOf("bell"))
                    if (state.locationId == "square") {
                        public += PublicEmission(item.dueAt, "notification",
                            mapOf("message" to "광장에서 종소리가 울렸다."))
                    }
                    if (action is GameAction.Rest) { interrupted = true; end = item.dueAt }
                }
                "secret.recalled" -> {
                    changes += DomainMutation("fixture.private_memory_activated", item.dueAt,
                        mapOf("actor_id" to "npc-herald", "secret_ref" to "hidden_knowledge"),
                        null, listOf("npc-herald"))
                    // No visible event: private NPC belief must NEVER reach a client.
                }
                else -> throw RuleProblem("UNKNOWN_SCHEDULE", "Unknown scheduled action")
            }
            executed += item.id
            if (interrupted) break
        }
        val elapsed = end - state.worldTime
        if (elapsed > 0) changes += DomainMutation("time.advanced", end,
            mapOf("delta_seconds" to elapsed), actor)
        var place = state.locationId
        when (action) {
            is GameAction.Speak -> {
                changes += DomainMutation("dialogue.spoken", end,
                    mapOf("target_id" to action.targetId, "utterance" to action.utterance),
                    actor, listOf(action.targetId))
                public += PublicEmission(end, "dialogue", mapOf("speaker_id" to action.targetId,
                    "text" to FixtureRules.npcReplies.getValue(action.targetId)))
            }
            is GameAction.Move -> {
                place = action.destinationLocationId
                changes += DomainMutation("character.moved", end,
                    mapOf("destination_location_id" to place), actor, listOf("player"))
                public += PublicEmission(end, "notification", mapOf("message" to "새로운 장소에 도착했다."))
            }
            is GameAction.Inspect -> {
                changes += DomainMutation("world.inspected", end,
                    mapOf("target_id" to action.targetId), actor, listOf(action.targetId))
                val text = if (action.targetId in setOf("bell", "shelves"))
                    "표면에 오래된 흔적이 보인다." else "조용히 주변을 살피고 있다."
                public += PublicEmission(end, "narrative", mapOf("text" to text))
            }
            is GameAction.Rest -> {
                changes += DomainMutation("character.rested", end,
                    mapOf("advanced_seconds" to elapsed, "interrupted" to interrupted),
                    actor, listOf("player"))
                public += PublicEmission(end, "notification",
                    mapOf("message" to if (interrupted) "휴식이 중단되었다." else "휴식을 마쳤다."))
            }
        }
        return TurnPlan("accepted", end, place, elapsed, interrupted,
            changes, public, executed)
    }
}

/** Rebuild only projections that are fully event modeled in fixture rules. Never re-run LLM. */
object ReplayReducer {
    fun reduce(events: List<DomainMutation>): Pair<Long, String> {
        var time = 0L
        var location = "square"
        for (event in events) {
            when (event.type) {
                "time.advanced" -> time = Math.addExact(time,
                    (event.payload["delta_seconds"] as Number).toLong())
                "character.moved" -> location = event.payload.getValue("destination_location_id").toString()
            }
        }
        return Pair(time, location)
    }
}
