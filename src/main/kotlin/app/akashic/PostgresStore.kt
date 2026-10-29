package app.akashic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class EngineException(val status: Int, val code: String, override val message: String,
                      val latestViewRevision: String? = null) : RuntimeException(message) {
    fun body(): Map<String, Any> {
        val error = mutableMapOf<String, Any>(
            "code" to code, "message" to message,
            "retryable" to (code in setOf("STALE_VIEW", "INTERNAL_ERROR", "RESOURCE_LIMIT")),
        )
        latestViewRevision?.let { error["latest_view_revision"] = it }
        return mapOf("error" to error)
    }
}

object Wire {
    val json = ObjectMapper()
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    fun text(value: JsonNode?, max: Int, label: String, min: Int = 1): String {
        val s = if (value?.isTextual == true) value.asText() else ""
        if (s.length !in min..max) throw EngineException(400, "BAD_REQUEST", "Invalid $label")
        return s
    }
    fun id(value: JsonNode?, label: String): String = text(value, 128, label).also {
        if (!idPattern.matches(it)) throw EngineException(400,"BAD_REQUEST","Invalid $label")
    }
    fun objectFields(value: JsonNode, expected: Set<String>, required: Set<String>, label: String) {
        if (!value.isObject) throw EngineException(400,"BAD_REQUEST","$label must be an object")
        val fields = value.fieldNames().asSequence().toSet()
        if (!fields.containsAll(required) || !expected.containsAll(fields))
            throw EngineException(400,"BAD_REQUEST","Invalid $label fields")
    }
    fun canonical(node: JsonNode): String = when {
        node.isObject -> node.fields().asSequence().toList().sortedBy { it.key }
            .joinToString(",", "{", "}") { (key, value) -> json.writeValueAsString(key) + ":" + canonical(value) }
        node.isArray -> node.elements().asSequence().joinToString(",", "[", "]") { canonical(it) }
        else -> json.writeValueAsString(node)
    }
    fun encode(any: Any?): String = json.writeValueAsString(any)
    fun decodeCommandResult(raw: String): Map<String, Any?> {
        val node = json.readTree(raw)
        val visible = node.get("visible_events").map { ev -> mapOf(
            "event_id" to ev.get("event_id").asText(),
            "cursor" to ev.get("cursor").asText(),
            "world_time" to ev.get("world_time").asLong(),
            "kind" to ev.get("kind").asText(),
            "payload" to json.convertValue(ev.get("payload"), Map::class.java)) }
        val response = mutableMapOf<String,Any?>(
            "command_id" to node.get("command_id").asText(),
            "status" to node.get("status").asText(),
            "view_revision" to node.get("view_revision").asText(),
            "world_time" to node.get("world_time").asLong(),
            "advanced_seconds" to node.get("advanced_seconds").asLong(),
            "visible_events" to visible)
        if (node.has("interrupted")) response["interrupted"] = node.get("interrupted").asBoolean()
        if (node.has("reason_code")) response["reason_code"] = node.get("reason_code").asText()
        return response
    }
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun token(prefix: String): String = prefix + "-" + UUID.randomUUID()
    fun longs(value: JsonNode?, min: Long, max: Long, label: String): Long {
        if (value == null || !value.isIntegralNumber || !value.canConvertToLong())
            throw EngineException(400,"BAD_REQUEST","Invalid $label")
        val n = value.longValue()
        if (n !in min..max) throw EngineException(400,"BAD_REQUEST","Invalid $label")
        return n
    }
    fun action(req: JsonNode): GameAction {
        val fields = setOf("command_id","actor_id","expected_view_revision","type","payload")
        objectFields(req, fields, fields, "command")
        id(req.get("command_id"),"command_id")
        id(req.get("actor_id"),"actor_id")
        text(req.get("expected_view_revision"),256,"expected_view_revision")
        val type = text(req.get("type"),128,"type")
        if (type !in setOf("dialogue.speak","character.move","world.inspect","character.rest"))
            throw EngineException(422, "UNKNOWN_COMMAND", "Unrecognized command")
        val payload = req.get("payload") ?: throw EngineException(400,"BAD_REQUEST","Missing payload")
        return when(type) {
            "dialogue.speak" -> {
                objectFields(payload,setOf("target_id","utterance"),setOf("target_id","utterance"),"speak payload")
                GameAction.Speak(id(payload.get("target_id"),"target_id"),text(payload.get("utterance"),2000,"utterance"))
            }
            "character.move" -> {
                objectFields(payload,setOf("destination_location_id"),setOf("destination_location_id"),"move payload")
                GameAction.Move(id(payload.get("destination_location_id"),"destination_location_id"))
            }
            "world.inspect" -> {
                objectFields(payload,setOf("target_id"),setOf("target_id"),"inspect payload")
                GameAction.Inspect(id(payload.get("target_id"),"target_id"))
            }
            else -> {
                objectFields(payload,setOf("requested_seconds"),setOf("requested_seconds"),"rest payload")
                GameAction.Rest(longs(payload.get("requested_seconds"),1,604800,"requested_seconds"))
            }
        }
    }
}

fun dataSourceFromEnv(env: Map<String, String> = System.getenv()): HikariDataSource {
    val url = env["DATABASE_URL"] ?: error("DATABASE_URL required")
    val dbUsername = env["DATABASE_USER"] ?: error("DATABASE_USER required")
    val dbPassword = env["DATABASE_PASSWORD"] ?: error("DATABASE_PASSWORD required")
    require(url.startsWith("jdbc:postgresql://")) { "Only PostgreSQL JDBC URLs accepted" }
    return HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        this.username = dbUsername
        this.password = dbPassword
        maximumPoolSize = (env["DB_POOL_SIZE"]?.toIntOrNull() ?: 8).coerceIn(1,32)
        minimumIdle = 0
        connectionTimeout = 10000
        isAutoCommit = true
        poolName = "AkashicPool"
    })
}
fun migrate(dataSource: DataSource) {
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
        .baselineOnMigrate(false).load().migrate()
}

class PostgresStore(private val source: DataSource, private val engine: TurnEngine = TurnEngine()) {
    private fun <T> tx(block: (Connection) -> T): T = source.connection.use { conn ->
        conn.autoCommit = false
        conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        try { val result = block(conn); conn.commit(); result }
        catch (ex: Exception) { conn.rollback(); throw ex }
    }
    private fun PreparedStatement.setStrings(vararg args: Any?) {
        for ((i, x) in args.withIndex()) {
            when (x) {
                null -> setNull(i + 1, java.sql.Types.VARCHAR)
                is String -> setString(i + 1, x)
                is Long -> setLong(i + 1, x)
                is Int -> setInt(i + 1, x)
                is Boolean -> setBoolean(i + 1, x)
                is Instant -> setObject(i + 1, java.sql.Timestamp.from(x))
                else -> error("Unsupported parameter type")
            }
        }
    }
    private fun execute(conn: Connection, sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { ps -> ps.setStrings(*args); ps.executeUpdate() }
    }
    private fun <T> single(conn: Connection, sql: String, vararg args: Any?, mapper: (ResultSet) -> T): T? =
        conn.prepareStatement(sql).use { ps ->
            ps.setStrings(*args)
            ps.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }
        }
    private fun loadWorld(conn: Connection, id: String, actor: String, lock: Boolean = false): WorldState {
        val sql = """SELECT id,owner_id,canon_version_id,rule_version,world_time,
            location_id,clock_mode,view_revision,last_seq FROM worlds WHERE id=? AND owner_id=?""" +
            if (lock) " FOR UPDATE" else ""
        return single(conn,sql,id,actor) { rs ->
            WorldState(rs.getString("id"), rs.getString("owner_id"), rs.getString("canon_version_id"),
                rs.getString("rule_version"),rs.getLong("world_time"),rs.getString("location_id"),
                rs.getString("clock_mode"),rs.getString("view_revision"),rs.getLong("last_seq"))
        } ?: throw EngineException(404,"WORLD_NOT_FOUND","World not found")
    }
    fun createWorld(actor: String, req: JsonNode): Map<String, Any> {
        Wire.objectFields(req,setOf("canon_version_id","seed"),setOf("canon_version_id"),"world creation")
        val canon = Wire.id(req.get("canon_version_id"),"canon_version_id")
        if (canon != FixtureRules.CANON_VERSION)
            throw EngineException(422,"RULE_REJECTED","Canon version not published in fixture")
        val seed = if (req.has("seed")) Wire.longs(req.get("seed"),0,Long.MAX_VALUE,"seed")
            else SecureRandom().nextLong(Long.MAX_VALUE)
        val id = Wire.token("w")
        val rev = Wire.token("vr")
        tx { conn ->
            execute(conn, """INSERT INTO worlds
              (id,owner_id,canon_version_id,rule_version,world_seed,view_revision)
              VALUES (?,?,?,?,?,?)""",id,actor,canon,FixtureRules.RULE_VERSION,seed,rev)
            for (initial in FixtureRules.initialSchedule) {
                execute(conn, """INSERT INTO scheduled_events
                  (id,world_id,due_at_world,priority,insertion_seq,kind)
                  VALUES (?,?,?,?,?,?)""",
                    id + "-" + initial.id,id,initial.dueAt,initial.priority,initial.insertionSeq,initial.kind)
            }
            execute(conn, """INSERT INTO actor_beliefs
              (world_id,actor_id,fact_key,fact_value) VALUES (?,?,?,?)""",
                id,"npc-herald","hidden_knowledge",FixtureRules.PRIVATE_NPC_BELIEF)
        }
        return mapOf("world_id" to id,"canon_version_id" to canon,
            "rule_version" to FixtureRules.RULE_VERSION,"world_time" to 0L,"view_revision" to rev)
    }
    fun view(id: String, actor: String): Map<String, Any> = source.connection.use { conn ->
        val state = loadWorld(conn,id,actor)
        mapOf("world_id" to id, "viewer_id" to actor,"view_revision" to state.viewRevision,
            "world_time" to state.worldTime,"clock_mode" to state.clockMode,
            "entities" to FixtureRules.entities(state.locationId),"notices" to emptyList<String>())
    }
    fun command(id: String, actor: String, req: JsonNode): Map<String, Any?> {
        if (req.get("actor_id")?.asText() != actor)
            throw EngineException(403,"FORBIDDEN","Actor mismatch")
        val action = Wire.action(req)
        val commandId = Wire.id(req.get("command_id"),"command_id")
        val requestHash = Wire.sha256(Wire.canonical(req))
        return tx { conn ->
            val state = loadWorld(conn,id,actor,lock=true)
            val previous = single(conn, """SELECT request_sha256,response_json FROM command_results
              WHERE world_id=? AND actor_id=? AND command_id=?""",id,actor,commandId) { rs ->
                Pair(rs.getString("request_sha256"),rs.getString("response_json"))
            }
            if (previous != null) {
                if (previous.first != requestHash)
                    throw EngineException(409,"COMMAND_ID_CONFLICT","Command ID reused with different payload")
                return@tx Wire.decodeCommandResult(previous.second)
            }
            if (state.viewRevision != req.get("expected_view_revision").asText())
                throw EngineException(409,"STALE_VIEW","Refresh before retry",state.viewRevision)
            val plan = try { engine.plan(state,actor,action,pending(conn,id)) }
                catch (e: RuleProblem) { throw EngineException(422,e.code,e.message ?: e.code) }
            val response: Map<String, Any?>
            if (plan.status == "rejected") {
                response = mapOf("command_id" to commandId,"status" to "rejected",
                    "view_revision" to state.viewRevision,"world_time" to state.worldTime,
                    "advanced_seconds" to 0L,"visible_events" to emptyList<Any>(),
                    "reason_code" to plan.reason)
            } else {
                var seq = state.lastSeq
                for (event in plan.events) {
                    seq++
                    execute(conn,"""INSERT INTO world_events
                     (world_id,sequence,event_id,occurred_at_world,recorded_at_utc,type,
                     schema_version,actor_id,target_ids_json,payload_json,caused_by_json,command_id,rule_version)
                     VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,'[]'::jsonb,?,?)""",
                        id,seq,Wire.token("evt"),event.at,Instant.now(),event.type,1,event.actorId,
                        Wire.encode(event.targetIds),Wire.encode(event.payload),commandId,state.ruleVersion)
                }
                for (scheduleId in plan.executedScheduleIds)
                    execute(conn,"""UPDATE scheduled_events SET executed=TRUE
                      WHERE id=? AND world_id=? AND executed=FALSE""",scheduleId,id)
                val public = plan.emissions.map { emission ->
                    val eventId = Wire.token("ev")
                    val cursor = Wire.token("cur")
                    execute(conn,"""INSERT INTO visible_events
                      (world_id,viewer_id,event_id,cursor,world_time,kind,payload_json)
                      VALUES (?,?,?,?,?,?,?::jsonb)""",
                        id,actor,eventId,cursor,emission.at,emission.kind,Wire.encode(emission.payload))
                    mapOf("event_id" to eventId,"cursor" to cursor,
                        "world_time" to emission.at,"kind" to emission.kind,"payload" to emission.payload)
                }
                val revision = Wire.token("vr")
                execute(conn,"""UPDATE worlds SET world_time=?,location_id=?,view_revision=?,last_seq=?
                   WHERE id=?""",plan.nextTime,plan.nextLocation,revision,seq,id)
                val r = mutableMapOf<String,Any?>(
                    "command_id" to commandId,"status" to "accepted",
                    "view_revision" to revision,"world_time" to plan.nextTime,
                    "advanced_seconds" to plan.elapsed,"visible_events" to public)
                if (action is GameAction.Rest) r["interrupted"] = plan.interrupted
                response = r
            }
            execute(conn,"""INSERT INTO command_results
              (world_id,actor_id,command_id,request_sha256,response_json)
              VALUES (?,?,?,?,?::jsonb)""",id,actor,commandId,requestHash,Wire.encode(response))
            response
        }
    }
    private fun pending(conn: Connection, id: String): List<ScheduledAction> =
        conn.prepareStatement("""SELECT id,due_at_world,priority,insertion_seq,kind
          FROM scheduled_events WHERE world_id=? AND executed=FALSE
          ORDER BY due_at_world,priority,insertion_seq,id""").use { ps ->
            ps.setString(1,id)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ScheduledAction(rs.getString("id"),
                    rs.getLong("due_at_world"),rs.getInt("priority"),
                    rs.getLong("insertion_seq"),rs.getString("kind"))) }
            }
        }
    fun events(id: String, actor: String, cursor: String?): Map<String, Any?> = source.connection.use { conn ->
        loadWorld(conn,id,actor)
        var floor = 0L
        if (cursor != null)
            floor = single(conn,"""SELECT ordinal FROM visible_events
              WHERE world_id=? AND viewer_id=? AND cursor=?""",id,actor,cursor) { rs -> rs.getLong(1) }
                ?: throw EngineException(410,"CURSOR_EXPIRED","Resync from full view")
        val all = conn.prepareStatement("""SELECT event_id,cursor,world_time,kind,payload_json
          FROM visible_events WHERE world_id=? AND viewer_id=? AND ordinal>?
          ORDER BY ordinal LIMIT 101""").use { ps ->
            ps.setString(1,id); ps.setString(2,actor); ps.setLong(3,floor)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(mapOf(
                    "event_id" to rs.getString("event_id"),"cursor" to rs.getString("cursor"),
                    "world_time" to rs.getLong("world_time"),"kind" to rs.getString("kind"),
                    "payload" to Wire.json.readValue(rs.getString("payload_json"),Map::class.java))) }
            }
        }
        val page = all.take(100)
        mapOf("events" to page,"next_cursor" to (page.lastOrNull()?.get("cursor") ?: cursor),
            "has_more" to (all.size > 100))
    }
    fun replayConsistent(id: String, actor: String): Boolean = source.connection.use { conn ->
        val state = loadWorld(conn,id,actor)
        val events = conn.prepareStatement("""SELECT type,occurred_at_world,payload_json
          FROM world_events WHERE world_id=? ORDER BY sequence""").use { ps ->
            ps.setString(1,id)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) {
                    val payload = Wire.json.readValue(rs.getString("payload_json"),Map::class.java)
                    @Suppress("UNCHECKED_CAST")
                    add(DomainMutation(rs.getString("type"),rs.getLong("occurred_at_world"),
                        payload as Map<String,Any>))
                } }
            }
        }
        ReplayReducer.reduce(events) == Pair(state.worldTime,state.locationId)
    }
}
