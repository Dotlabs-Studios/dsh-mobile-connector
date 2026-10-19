package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the frame reader and the three frames worth a notification. */
class WatchFeedTest {

    @Test
    fun `a data frame is completed by the blank line`() {
        val parser = SseParser()
        assertNull(parser.feed("data: {\"event\":\"ready\"}"))
        assertEquals("{\"event\":\"ready\"}", parser.feed(""))
    }

    @Test
    fun `comments and retry hints are ignored`() {
        val parser = SseParser()
        assertNull(parser.feed(": keep-alive"))
        assertNull(parser.feed("retry: 3000"))
        assertNull(parser.feed(""))
        assertNull(parser.feed("data:{\"event\":\"ready\"}"))
        assertEquals("{\"event\":\"ready\"}", parser.feed(""))
    }

    @Test
    fun `multi-line data is joined with a newline`() {
        val parser = SseParser()
        parser.feed("data: {\"a\":1,")
        parser.feed("data: \"b\":2}")
        assertEquals("{\"a\":1,\n\"b\":2}", parser.feed(""))
    }

    @Test
    fun `carriage returns do not leak into the payload`() {
        val parser = SseParser()
        parser.feed("data: x\r")
        assertEquals("x", parser.feed("\r"))
    }

    @Test
    fun `an approval frame becomes an approval event`() {
        val payload = """
            {"event":"approval-request","approval":{"id":"approval-1-abc","sessionId":"s-1",
             "toolName":"pwsh","callId":"call-1","reason":"run the build","displayReason":"Run the build"}}
        """.trimIndent()
        val event = watchEvent(payload) as WatchEvent.Approval
        assertEquals("approval-1-abc", event.approvalId)
        assertEquals("s-1", event.sessionId)
        assertEquals("pwsh", event.toolName)
        assertEquals("Run the build", event.prompt)
    }

    @Test
    fun `a question frame takes the first question's text`() {
        val payload = """
            {"event":"question-request","question":{"id":"q-1","sessionId":"s-1","expired":false,
             "questions":[{"id":"q0","question":"Quale cartella?"}]}}
        """.trimIndent()
        val event = watchEvent(payload) as WatchEvent.Question
        assertEquals("q-1", event.questionId)
        assertEquals("Quale cartella?", event.text)
    }

    @Test
    fun `a running status is not a notification but a stopped one is`() {
        assertNull(watchEvent("""{"event":"status","sessionId":"s-1","running":true}"""))
        val ended = watchEvent("""{"event":"status","sessionId":"s-1","running":false}""") as WatchEvent.TurnEnded
        assertEquals("s-1", ended.sessionId)
    }

    @Test
    fun `frames that are not for the watch service are ignored`() {
        assertNull(watchEvent("""{"event":"ready","port":4380}"""))
        assertNull(watchEvent("""{"event":"approval-settled","approvalId":"x"}"""))
        assertNull(watchEvent("""{"event":"session-event","sessionId":"s","entry":{"kind":"message"}}"""))
        assertNull(watchEvent("""{"event":"permission-changed","sessionId":"s","preset":"read-only"}"""))
        assertNull(watchEvent("not json"))
        assertNull(watchEvent(""))
    }

    @Test
    fun `a malformed approval frame is dropped rather than announced`() {
        assertNull(watchEvent("""{"event":"approval-request","approval":{"toolName":"pwsh"}}"""))
    }
}
