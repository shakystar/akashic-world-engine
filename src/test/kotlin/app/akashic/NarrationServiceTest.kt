package app.akashic

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NarrationServiceTest {
    private val visible = mapOf<String, Any>("entities" to listOf(
        mapOf("id" to "player", "kind" to "person"),
        mapOf("id" to "npc-keeper", "kind" to "person", "display_name" to "여관 주인")
    ))
    private val generated = """{"choices":[{"message":{"content":"어서 오시오. 빈방이 있는지 장부를 확인하겠소."}}]}"""

    @Test fun staysNonAuthoritativeAndConstrainsPrompt() = runBlocking {
        var request = ""
        val svc = NarrationService(mapOf("AUTH_MODE" to "oidc")) { body, _ ->
            request = body
            generated
        }
        val answer = svc.reply("u-test", "w-fixture", visible, "안녕하세요. 잘 곳이 있는지요?")
        assertEquals(false, answer["committed"])
        assertEquals(true, answer["narration_only"])
        assertEquals("npc-keeper", answer["speaker_id"])
        assertEquals("어서 오시오. 빈방이 있는지 장부를 확인하겠소.", answer["text"])
        assertFalse(request.contains("FIXTURE_SECRET"))
        assertTrue(request.contains("사실로 확정하지 말라"))
    }

    @Test fun refusesMissingProviderOrUnauthorizedMode() = runBlocking {
        val locked = NarrationService(mapOf("AUTH_MODE" to "locked")) { _, _ -> generated }
        assertEquals(503, kotlin.runCatching {
            runBlocking { locked.reply("u1", "w1", visible, "안녕") }
        }.exceptionOrNull().let { (it as EngineException).status })
        val noKey = NarrationService(mapOf("AUTH_MODE" to "oidc"))
        assertEquals(503, kotlin.runCatching {
            runBlocking { noKey.reply("u1", "w1", visible, "안녕") }
        }.exceptionOrNull().let { (it as EngineException).status })
    }

    @Test fun rejectsInvisibleNpcWithoutSendingRequest() = runBlocking {
        var called = false
        val svc = NarrationService(mapOf("AUTH_MODE" to "oidc")) { _, _ ->
            called = true
            generated
        }
        val error = kotlin.runCatching {
            svc.reply("u1", "w1", mapOf("entities" to emptyList<Any>()), "안녕")
        }.exceptionOrNull() as EngineException
        assertEquals(422, error.status)
        assertFalse(called)
    }
}
