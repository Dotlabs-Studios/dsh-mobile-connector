package dev.dsh.mobile.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Wire-contract tests for [MobileApi].
 *
 * Every model in this app was written by hand against the plugin's routes, so
 * a single misnamed field would break the client at runtime on a phone. These
 * tests stand a real HTTP server in for the harness and assert the exact
 * request bodies the app produces and the exact responses it must survive.
 *
 * The stub is a hand-rolled HTTP/1.1 server rather than MockWebServer so the
 * test adds no dependency: OkHttp is the only client, and it always sends a
 * Content-Length, which is all the parsing this needs.
 */
class MobileApiTest {
    private lateinit var server: ServerSocket
    private var baseUrl: String = ""
    private val routes = mutableMapOf<String, StubResponse>()
    private val received = mutableListOf<Received>()

    /** One canned response. */
    private data class StubResponse(val status: Int, val body: String)

    /** One captured request. */
    private data class Received(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val body: String,
    )

    @Before
    fun startServer() {
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true, name = "stub-http") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    @After
    fun stopServer() {
        server.close()
    }

    /** Register one stub route. */
    private fun stub(path: String, status: Int, body: String) {
        routes[path] = StubResponse(status, body)
    }

    /**
     * Percent-decode one query value the way the host's `URLSearchParams` reads
     * it: `%XX` becomes its byte, a bare `+` stays a plus sign.
     *
     * @param value the raw wire value.
     * @returns the decoded text.
     */
    private fun percentDecode(value: String): String {
        val out = ByteArray(value.length)
        var length = 0
        var index = 0
        while (index < value.length) {
            val byte = value.substring(index + 1, minOf(index + 3, value.length))
                .takeIf { value[index] == '%' && index + 3 <= value.length }
                ?.toIntOrNull(16)
            if (byte != null) {
                out[length++] = byte.toByte()
                index += 3
            } else {
                out[length++] = value[index].code.toByte()
                index += 1
            }
        }
        return out.copyOf(length).toString(Charsets.UTF_8)
    }

    /**
     * Read one parameter out of a captured query string.
     *
     * @param query the raw query, or null when the request carried none.
     * @param name the parameter to read.
     * @returns the decoded value, or null when the parameter is absent.
     */
    private fun queryValue(query: String?, name: String): String? = query
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.let { percentDecode(it.substringAfter('=', "")) }

    /** Read one request, record it, and answer from the stub table. */
    private fun serve(socket: Socket) {
        socket.use { open ->
            val input = BufferedInputStream(open.getInputStream())
            val output = BufferedOutputStream(open.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "GET" }
            val target = parts.getOrElse(1) { "/" }

            var contentLength = 0
            var authorization: String? = null
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                val name = header.substringBefore(':').trim().lowercase()
                val value = header.substringAfter(':', "").trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "authorization" -> authorization = value
                }
            }
            val bodyBytes = if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(buffer, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                buffer
            } else {
                ByteArray(0)
            }

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").ifEmpty { null }
            synchronized(received) {
                received += Received(method, path, query, authorization, bodyBytes.toString(Charsets.UTF_8))
            }

            val stub = routes[path] ?: StubResponse(404, """{"error":{"code":"not-found","message":"no stub"}}""")
            val bytes = stub.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${stub.status} ${if (stub.status < 300) "OK" else "Error"}\r\n")
                append("content-type: application/json; charset=utf-8\r\n")
                append("content-length: ${bytes.size}\r\n")
                append("connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    /**
     * Read one CRLF-terminated line.
     *
     * @param input the stream to read.
     * @return the line without its terminator, or null at end of stream.
     */
    private fun readLine(input: BufferedInputStream): String? {
        val text = StringBuilder()
        while (true) {
            val next = input.read()
            if (next < 0) return if (text.isEmpty()) null else text.toString()
            if (next == '\n'.code) return text.toString().trimEnd('\r')
            text.append(next.toChar())
        }
    }

    /** The request the stub captured last. */
    private fun lastRequest(): Received = synchronized(received) { received.last() }

    @Test
    fun `pair sends the code and device and decodes a token`() = runBlocking {
        stub("/mobile/pair", 200, """{"token":"tok-abc","device":"Pixel 8","reused":false}""")
        val api = MobileApi(baseUrl, null)

        val response = api.pair("CODE-1", "Pixel 8")

        assertEquals("tok-abc", response.token)
        assertEquals("Pixel 8", response.device)
        assertEquals(false, response.reused)
        val sent = lastRequest()
        assertEquals("POST", sent.method)
        assertEquals("/mobile/pair", sent.path)
        assertTrue("pairing must not carry a token yet", sent.authorization == null)
        assertTrue("code is sent verbatim", sent.body.contains("\"code\":\"CODE-1\""))
        assertTrue("device is sent verbatim", sent.body.contains("\"device\":\"Pixel 8\""))
    }

    @Test
    fun `sessions sends the bearer token and decodes every summary field`() = runBlocking {
        stub(
            "/mobile/sessions",
            200,
            """
            {"items":[
              {"id":"session-aaaa","title":"Chat dal PC","cwd":"H:\\Documenti\\ws",
               "running":true,"agentAvailable":true,"blank":false,
               "updatedAt":1760000000800,"lastPromptAt":1760000000700,
               "parentSessionId":null,"origin":null},
              {"id":"session-bbbb","title":null,"running":false,
               "agentAvailable":false,"blank":true,"updatedAt":1759000000000}
            ]}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val items = api.sessions(includeSubagents = true, query = "dal pc")

        assertEquals(2, items.size)
        assertEquals("Bearer tok-abc", lastRequest().authorization)
        assertTrue("subagents flag is forwarded", lastRequest().query!!.contains("subagents=1"))
        assertTrue("query is URL encoded", lastRequest().query!!.contains("q=dal+pc") || lastRequest().query!!.contains("q=dal%20pc"))

        val first = items[0]
        assertEquals("session-aaaa", first.id)
        assertEquals("Chat dal PC", first.title)
        assertEquals("H:\\Documenti\\ws", first.cwd)
        assertTrue(first.running)
        assertTrue(first.agentAvailable)
        assertEquals(1760000000800L, first.updatedAt)
        assertEquals(1760000000700L, first.lastPromptAt)

        val second = items[1]
        assertNull(second.title)
        // A session without a title still needs a readable label, and the
        // workspace folder is the fallback the list renders.
        assertEquals("session-bbbb", second.displayTitle)
    }

    @Test
    fun `display title prefers the title, then the folder name, then the id`() {
        assertEquals("Titolo", SessionSummary(id = "s", title = "Titolo", cwd = "C:\\a\\b").displayTitle)
        assertEquals("b", SessionSummary(id = "s", title = null, cwd = "C:\\a\\b").displayTitle)
        assertEquals("b", SessionSummary(id = "s", title = "   ", cwd = "C:\\a\\b").displayTitle)
        assertEquals("b", SessionSummary(id = "s", title = null, cwd = "/home/me/b").displayTitle)
        assertEquals("b", SessionSummary(id = "s", title = null, cwd = "C:/a/b/").displayTitle)
        assertEquals("s", SessionSummary(id = "s", title = null, cwd = null).displayTitle)
    }

    @Test
    fun `messages decodes a transcript and forwards paging parameters`() = runBlocking {
        stub(
            "/mobile/sessions/session-aaaa/messages",
            200,
            """
            {"sessionId":"session-aaaa","cursor":42,"hasMore":true,"entries":[
              {"kind":"message","seq":40,"type":"user/message","role":"user","time":1760000000700,
               "content":[{"type":"text","text":"ciao"},{"type":"image","attachmentId":"sha256:abc",
                           "mediaType":"image/png","width":8,"height":8,"name":"foto.png"}]},
              {"kind":"tool-call","seq":41,"type":"tool/call","name":"pwsh","arguments":"{\"command\":\"ls\"}",
               "turn":1,"step":1,"callId":"call-1"},
              {"kind":"tool-result","seq":42,"type":"tool/result","isError":true,
               "content":[{"type":"text","text":"boom"}],"name":"pwsh"}
            ]}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val page = api.messages("session-aaaa", beforeSeq = 50, limit = 25)

        assertEquals(42, page.cursor)
        assertTrue(page.hasMore)
        assertEquals(3, page.entries.size)
        assertTrue("limit is forwarded", lastRequest().query!!.contains("limit=25"))
        assertTrue("beforeSeq is forwarded", lastRequest().query!!.contains("beforeSeq=50"))

        val message = page.entries[0]
        assertEquals("message", message.kind)
        assertEquals("user", message.role)
        assertEquals(40, message.seq)
        assertTrue(message.isMessage)
        assertEquals("ciao", message.content[0].text)
        assertEquals("sha256:abc", message.content[1].attachmentId)
        assertEquals(8, message.content[1].width)

        val toolCall = page.entries[1]
        assertEquals("pwsh", toolCall.name)
        assertEquals("""{"command":"ls"}""", toolCall.arguments)
        assertTrue("tool entries are not chat bubbles", !toolCall.isMessage)

        val toolResult = page.entries[2]
        assertEquals(true, toolResult.isError)
        assertEquals("boom", toolResult.content[0].text)
    }

    @Test
    fun `messages can ask for what is new and decodes the paging cursor`() = runBlocking {
        stub(
            "/mobile/sessions/session-aaaa/messages",
            200,
            """
            {"sessionId":"session-aaaa","cursor":9,"hasMore":false,"nextBeforeSeq":7,"afterSeq":6,
             "entries":[{"kind":"message","seq":8,"type":"assistant/message","role":"assistant",
                         "content":[{"type":"text","text":"ecco"}]}]}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val page = api.messages("session-aaaa", afterSeq = 6, limit = 30)

        assertEquals(1, page.entries.size)
        assertEquals(8, page.entries[0].seq)
        assertEquals(7, page.nextBeforeSeq)
        assertTrue("afterSeq is forwarded", lastRequest().query!!.contains("afterSeq=6"))
        assertTrue("an incremental read sends no upper bound", !lastRequest().query!!.contains("beforeSeq="))
    }

    @Test
    fun `the pulse decodes sessions, projects and both queues`() = runBlocking {
        stub(
            "/mobile/pulse",
            200,
            """
            {"sessions":[{"id":"session-aaaa","title":"Chat","running":true}],
             "workspaces":[{"workspaceId":"ws-1","path":"K:\\\\Repository\\\\Ashfall","title":"Ashfall"}],
             "workspacesAvailable":true,
             "approvals":[{"id":"approval-1","toolName":"pwsh","callId":"call-1"}],
             "questions":[{"id":"q-1","questions":[{"id":"q0","question":"Quale?"}]}],
             "approvalsPending":1,"questionsPending":1}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val pulse = api.pulse(includeSubagents = true, query = "ash")

        assertEquals(1, pulse.sessions.size)
        assertEquals("session-aaaa", pulse.sessions[0].id)
        assertEquals("Ashfall", pulse.workspaces[0].title)
        assertEquals(true, pulse.workspacesAvailable)
        assertEquals("approval-1", pulse.approvals[0].id)
        assertEquals(1, pulse.questions[0].questions.size)
        assertEquals("Quale?", pulse.questions[0].questions[0].question)
        assertTrue("the filters are forwarded", lastRequest().query!!.contains("subagents=1"))
        assertTrue("the search travels with the pulse", lastRequest().query!!.contains("q=ash"))
    }

    @Test
    fun `devices decode and a revoke is addressed by id`() = runBlocking {
        stub(
            "/mobile/devices",
            200,
            """{"items":[{"id":"dev-1","name":"Pixel 8","current":true,"lastSeenAt":1760000000000}]}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val devices = api.devices()

        assertEquals(1, devices.size)
        assertEquals("Pixel 8", devices[0].displayName)
        assertEquals(true, devices[0].current)
        assertTrue(devices[0].lastSeenAt > 0L)

        stub("/mobile/devices/dev-1", 200, """{"revoked":true,"id":"dev-1","self":true}""")
        val revoked = api.revokeDevice("dev-1")
        assertTrue(revoked.self)
        assertEquals("DELETE", lastRequest().method)
        assertEquals("/mobile/devices/dev-1", lastRequest().path)
    }

    @Test
    fun `send posts text and inline photos in the documented shape`() = runBlocking {
        stub("/mobile/sessions/session-aaaa/messages", 202, """{"accepted":true}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.send(
            sessionId = "session-aaaa",
            text = "guarda",
            images = listOf(OutgoingImage(base64 = "QUJD", mediaType = "image/png", name = "foto.png")),
            steer = true,
        )

        val body = lastRequest().body
        assertTrue("text is present", body.contains("\"text\":\"guarda\""))
        assertTrue("steer mode is forwarded", body.contains("\"mode\":\"steer\""))
        assertTrue("media type is present", body.contains("\"mediaType\":\"image/png\""))
        assertTrue("base64 payload is present", body.contains("\"data\":\"QUJD\""))
        assertTrue("file name is present", body.contains("\"name\":\"foto.png\""))
        assertTrue("photo messages must not carry file receipts", !body.contains("attachments"))
    }

    @Test
    fun `send omits the mode when queueing and supports a photo-only message`() = runBlocking {
        stub("/mobile/sessions/session-aaaa/messages", 202, """{"accepted":true}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.send(
            sessionId = "session-aaaa",
            text = "   ",
            images = listOf(OutgoingImage(base64 = "QUJD", mediaType = "image/jpeg")),
        )

        val body = lastRequest().body
        assertTrue("blank text is not sent", !body.contains("\"text\""))
        assertTrue("queue is the default and needs no field", !body.contains("\"mode\""))
        assertTrue("the photo still rides along", body.contains("\"data\":\"QUJD\""))
    }

    @Test
    fun `send carries the client id only when a retry has to be recognised`() = runBlocking {
        stub("/mobile/sessions/session-aaaa/messages", 202, """{"accepted":true}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.send(sessionId = "session-aaaa", text = "primo", clientMessageId = "cm-1")
        assertTrue("the key travels so the host can answer a repeat", lastRequest().body.contains("\"clientMessageId\":\"cm-1\""))

        api.send(sessionId = "session-aaaa", text = "secondo")
        assertTrue("a normal send does not invent one", !lastRequest().body.contains("clientMessageId"))
    }

    @Test
    fun `api errors are decoded into ApiException with their code`() = runBlocking {
        stub(
            "/mobile/sessions/missing/messages",
            404,
            """{"error":{"code":"session/not-found","message":"session \"missing\" not found"}}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.messages("missing")
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("session/not-found", error.code)
            assertEquals(404, error.status)
            assertTrue(error.message.contains("missing"))
        }
    }

    @Test
    fun `an unreachable host reports a transport failure rather than crashing`() = runBlocking {
        val api = MobileApi("http://127.0.0.1:1", "tok-abc")
        try {
            api.status()
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("transport", error.code)
        }
    }

    @Test
    fun `a malformed success body reports a decode failure`() = runBlocking {
        stub("/mobile/status", 200, """{"ok": "yes please"}""")
        val api = MobileApi(baseUrl, "tok-abc")
        try {
            api.status()
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("decode", error.code)
        }
    }

    @Test
    fun `image urls are absolute and url encoded`() {
        val api = MobileApi("http://192.168.1.20:4380", "tok")
        // java.net.URLEncoder uses '+' for spaces, matching what the plugin's
        // route decoder expects; the id itself is opaque and must survive.
        assertEquals(
            "http://192.168.1.20:4380/mobile/sessions/session%2Fweird+id/images/sha256%3Aab%2Bcd/raw",
            api.imageUrl("session/weird id", "sha256:ab+cd"),
        )
        assertEquals(
            "http://192.168.1.20:4380/mobile/sessions/session-plain/images/sha256%3Aabc/raw",
            api.imageUrl("session-plain", "sha256:abc"),
        )
    }

    @Test
    fun `upload image posts canonical base64 and decodes the reference`() = runBlocking {
        stub(
            "/mobile/images",
            201,
            """{"attachmentId":"sha256:deadbeef","name":"rosso.png","bytes":268,"sha256":"deadbeef"}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")
        val base64 = Base64.getEncoder().encodeToString("hello".toByteArray())

        val uploaded = api.uploadImage(base64, "rosso.png")

        assertEquals("sha256:deadbeef", uploaded.attachmentId)
        assertEquals(268L, uploaded.bytes)
        assertTrue(lastRequest().body.contains("\"data\":\"$base64\""))
    }

    @Test
    fun `staging a file posts its bytes, its name, and the chat it belongs to`() = runBlocking {
        stub(
            "/mobile/files",
            201,
            """{"receiptId":"receipt-7","attachmentId":"sha256:deadbeef","name":"nota.md","bytes":11}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")
        val bytes = "# nota\nriga".toByteArray()

        val staged = api.uploadFile("session/one", "K:\\Repository\\nota.md", bytes)

        assertEquals("receipt-7", staged.receiptId)
        assertEquals("sha256:deadbeef", staged.attachmentId)
        assertEquals("nota.md", staged.name)
        assertEquals(11L, staged.bytes)
        val sent = lastRequest()
        assertEquals("POST", sent.method)
        assertEquals("/mobile/files", sent.path)
        assertTrue("the chat the receipt belongs to must travel", sent.query!!.contains("sessionId=session%2Fone"))
        assertTrue("the picked path is percent-encoded", sent.query!!.contains("name=K%3A%5CRepository%5Cnota.md"))
        assertEquals("# nota\nriga", sent.body)
    }

    @Test
    fun `a message names a staged file by its receipt and by nothing else`() = runBlocking {
        stub(
            "/mobile/sessions/session-a/messages",
            202,
            """{"accepted":true}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        api.send("session-a", "leggi questa nota", files = listOf(OutgoingFile("receipt-7", "nota.md", 11)))

        val body = lastRequest().body
        assertTrue("the receipt is what the prompt carries", body.contains(""""receiptId":"receipt-7""""))
        assertTrue("the bytes are already the host's", !body.contains("nota.md"))
        assertTrue("a file-only message is still a message", !body.contains("\"images\""))
    }

    @Test
    fun `a photo message carries no file field at all`() = runBlocking {
        stub("/mobile/sessions/session-a/messages", 202, """{"accepted":true}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.send("session-a", "guarda", images = listOf(OutgoingImage(base64 = "QUJD", mediaType = "image/png")))

        assertTrue("an absent list must not travel as an empty one", !lastRequest().body.contains("files"))
    }

    @Test
    fun `create session decodes the minted id`() = runBlocking {
        stub("/mobile/sessions", 201, """{"sessionId":"session-new","agentPreset":"standard"}""")
        val api = MobileApi(baseUrl, "tok-abc")

        val created = api.createSession()

        assertEquals("session-new", created.sessionId)
        assertEquals("standard", created.agentPreset)
        assertEquals("POST", lastRequest().method)
    }

    @Test
    fun `status decodes the paired device list`() = runBlocking {
        stub(
            "/mobile/status",
            200,
            """{"ok":true,"host":"0.0.0.0","port":4380,"pairingOpen":false,
                "devices":[{"name":"Pixel 8","createdAt":1,"lastSeenAt":2}],
                "sessionCount":39,"liveCount":2,"version":1}""".trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val status = api.status()

        assertTrue(status.ok)
        assertEquals(4380, status.port)
        assertEquals(39, status.sessionCount)
        assertEquals(false, status.pairingOpen)
        assertEquals("Pixel 8", status.devices.single().name)
    }

    @Test
    fun `status tolerates a host that still accepts pairing`() = runBlocking {
        stub("/mobile/status", 200, """{"ok":true,"host":"0.0.0.0","port":4380,"pairingOpen":true,"devices":[]}""")
        val api = MobileApi(baseUrl, "tok-abc")

        val status = api.status()

        assertTrue(status.pairingOpen)
        assertTrue(status.devices.isEmpty())
        assertEquals(0, status.sessionCount)
    }

    @Test
    fun `sessions decode the project each chat belongs to`() = runBlocking {
        stub(
            "/mobile/sessions",
            200,
            """
            {"items":[
              {"id":"session-aaaa","title":"Chat","cwd":"K:\\Repository\\Acme",
               "workspaceId":"ws-1","workspaceTitle":"Acme","updatedAt":1760000000800},
              {"id":"session-bbbb","title":null,"cwd":null,
               "workspaceId":null,"workspaceTitle":null,"updatedAt":1759000000000}
            ]}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val items = api.sessions()

        assertEquals("ws-1", items[0].workspaceId)
        assertEquals("Acme", items[0].workspaceTitle)
        assertNull("a chat outside every project has no group", items[1].workspaceId)
        assertEquals("a chat with no title and no directory falls back to its id", "session-bbbb", items[1].displayTitle)
    }

    @Test
    fun `workspaces decode the registry projection`() = runBlocking {
        stub(
            "/mobile/workspaces",
            200,
            """
            {"items":[
              {"workspaceId":"ws-1","path":"K:\\Repository\\Acme","title":"Acme",
               "sessionIds":["session-aaaa","session-bbbb"],
               "createdAt":"2026-10-01T00:00:00.000Z","updatedAt":"2026-10-02T00:00:00.000Z"},
              {"workspaceId":"ws-2","path":"D:\\Unreal Projects\\Nebula","title":"","sessionIds":[]}
            ]}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val items = api.workspaces()

        assertEquals(2, items.size)
        assertEquals("Bearer tok-abc", lastRequest().authorization)
        assertEquals("Acme", items[0].displayTitle)
        assertEquals(2, items[0].sessionIds.size)
        assertEquals("a blank title falls back to the folder name", "Nebula", items[1].displayTitle)
    }

    @Test
    fun `adopting a directory posts the path verbatim`() = runBlocking {
        stub(
            "/mobile/workspaces",
            201,
            """{"workspaceId":"ws-9","path":"K:\\Repository\\Nuovo","title":"Nuovo","sessionIds":[],"created":true}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val created = api.createWorkspace("K:\\Repository\\Nuovo")

        assertTrue(created.created)
        assertEquals("ws-9", created.workspaceId)
        assertEquals("POST", lastRequest().method)
        // Backslashes must survive JSON encoding: a doubled or stripped
        // separator would point the host at a directory that does not exist.
        assertTrue(
            "the Windows path is sent with single backslashes",
            lastRequest().body.contains("""K:\\Repository\\Nuovo"""),
        )
    }

    @Test
    fun `re-adopting a known directory reports it as unchanged`() = runBlocking {
        stub(
            "/mobile/workspaces",
            200,
            """{"workspaceId":"ws-1","path":"K:\\Repository\\Acme","title":"Acme","sessionIds":[],"created":false}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val reused = api.createWorkspace("K:\\Repository\\Acme")

        assertEquals(false, reused.created)
        assertEquals("ws-1", reused.workspaceId)
    }

    @Test
    fun `browsing decodes a level with its crumbs and volume roots`() = runBlocking {
        stub(
            "/mobile/dirs",
            200,
            """
            {"path":"K:\\Repository","home":"C:\\Users\\you",
             "crumbs":[{"name":"K:\\","path":"K:\\","hidden":false,"drive":true},
                       {"name":"Repository","path":"K:\\Repository","hidden":false}],
             "entries":[{"name":"dsh-mobile-connector","path":"K:\\Repository\\dsh-mobile-connector","hidden":false},
                        {"name":".git","path":"K:\\Repository\\.git","hidden":true,"drive":false}],
             "truncated":false}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val listing = api.browse("K:\\Repository")

        assertEquals("K:\\Repository", listing.path)
        assertEquals("C:\\Users\\you", listing.home)
        assertTrue("volume roots arrive as crumbs", listing.crumbs.first().drive)
        assertEquals(2, listing.entries.size)
        assertTrue("hidden folders are flagged, not hidden", listing.entries[1].hidden)
        assertEquals(false, listing.truncated)
        assertTrue("the path travels percent-encoded", lastRequest().query!!.startsWith("path=K%3A%5CRepository"))
    }

    @Test
    fun `browsing without a path asks the host for its home`() = runBlocking {
        stub(
            "/mobile/dirs",
            200,
            """{"path":"C:\\Users\\you","home":"C:\\Users\\you","crumbs":[],"entries":[],"truncated":false}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val listing = api.browse()

        assertNull("no path means no query the host could misread", lastRequest().query)
        assertEquals("C:\\Users\\you", listing.path)
    }

    @Test
    fun `creating a folder returns the refreshed level`() = runBlocking {
        stub(
            "/mobile/dirs",
            201,
            """
            {"created":"K:\\Repository\\Nuovo","path":"K:\\Repository","home":"C:\\Users\\you",
             "crumbs":[],"entries":[{"name":"Nuovo","path":"K:\\Repository\\Nuovo","hidden":false}],
             "truncated":false}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val listing = api.createDirectory("K:\\Repository", "Nuovo")

        assertEquals("K:\\Repository\\Nuovo", listing.created)
        assertEquals("POST", lastRequest().method)
        assertTrue(lastRequest().body.contains(""""parent":"K:\\Repository""""))
        assertTrue(lastRequest().body.contains(""""name":"Nuovo""""))
        assertEquals("Nuovo", listing.entries.single().name)
    }

    @Test
    fun `an unreadable host directory surfaces its own code`() = runBlocking {
        stub("/mobile/dirs", 404, """{"error":{"code":"directory-unreadable","message":"cannot list C:\\Nope"}}""")
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.browse("C:\\Nope")
            fail("an unreadable directory must not look like an empty one")
        } catch (error: ApiException) {
            assertEquals("directory-unreadable", error.code)
            assertEquals(404, error.status)
        }
    }

    @Test
    fun `a duplicate folder name surfaces as its own code`() = runBlocking {
        stub("/mobile/dirs", 409, """{"error":{"code":"directory-exists","message":"already there"}}""")
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.createDirectory("K:\\Repository", "Nuovo")
            fail("a duplicate must be reported, not silently ignored")
        } catch (error: ApiException) {
            assertEquals("directory-exists", error.code)
            assertEquals(409, error.status)
        }
    }

    @Test
    fun `the model catalog decodes providers, capabilities and failures`() = runBlocking {
        stub(
            "/mobile/models",
            200,
            """
            {"default":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"high"},
             "routableProviders":["deepseek-official","mimo"],
             "groups":[
               {"id":"deepseek-official","name":"DeepSeek",
                "models":[{"provider":"deepseek-official","id":"deepseek-flash","name":"deepseek-flash",
                           "vision":false,"contextWindow":128000,"maxTokens":8192}]},
               {"id":"mimo","name":"MiMo",
                "models":[{"provider":"mimo","id":"mimo-v2.6-flash","name":"mimo-v2.6-flash",
                           "reasoning":{"efforts":[{"id":"low","name":"Basso"},{"id":"high","name":"Alto"}],
                                        "defaultEffort":"low"},
                           "vision":true,"contextWindow":200000}]}],
             "failures":[{"id":"broken","name":"Broken","message":"no adapter"}]}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val catalog = api.models()

        assertEquals("deepseek-flash", catalog.default!!.model)
        assertEquals("deepseek-official/deepseek-flash", catalog.default!!.route)
        assertEquals(2, catalog.groups.size)
        val mimo = catalog.groups[1]
        assertEquals("MiMo", mimo.displayName)
        assertEquals(2, mimo.models.single().reasoning!!.efforts.size)
        assertTrue("a vision model is flagged", mimo.models.single().acceptsImages)
        assertEquals(200_000L, mimo.models.single().contextWindow)
        assertEquals(false, catalog.groups[0].models.single().acceptsImages)
        assertEquals("no adapter", catalog.failures.single().message)
        assertEquals(false, catalog.isEmpty)
    }

    @Test
    fun `a catalog whose providers all failed reports itself as empty`() = runBlocking {
        // An empty picker and a broken one are different states, and the screen
        // says different things about them.
        stub("/mobile/models", 200, """{"groups":[{"id":"a","name":"A","models":[]}],"failures":[]}""")
        val api = MobileApi(baseUrl, "tok-abc")

        assertTrue(api.models().isEmpty)
    }

    @Test
    fun `an unknown capability stays null rather than becoming false`() = runBlocking {
        stub(
            "/mobile/models",
            200,
            """{"groups":[{"id":"glm","name":"GLM","models":[{"provider":"glm","id":"glm-5.3","name":"glm-5.3"}]}],"failures":[]}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val model = api.models().groups.single().models.single()

        assertNull("untold is not the same as unsupported", model.vision)
        assertNull(model.contextWindow)
        assertEquals("glm-5.3", model.displayName)
    }

    @Test
    fun `selecting a model posts the route and decodes the answer`() = runBlocking {
        stub("/mobile/models", 200, """{"selected":{"provider":"mimo","model":"mimo-v2.6-pro","reasoningEffort":"high"}}""")
        val api = MobileApi(baseUrl, "tok-abc")

        val selected = api.selectModel("session-a", "mimo", "mimo-v2.6-pro", "high")

        assertEquals("POST", lastRequest().method)
        val body = lastRequest().body
        assertTrue(body.contains(""""sessionId":"session-a""""))
        assertTrue(body.contains(""""provider":"mimo""""))
        assertTrue(body.contains(""""model":"mimo-v2.6-pro""""))
        assertTrue(body.contains(""""reasoningEffort":"high""""))
        assertEquals("high", selected.reasoningEffort)
        assertEquals("mimo/mimo-v2.6-pro", selected.route)
    }

    @Test
    fun `selecting a model without an effort omits the field entirely`() = runBlocking {
        stub("/mobile/models", 200, """{"selected":{"provider":"glm","model":"glm-5.3"}}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.selectModel("session-a", "glm", "glm-5.3")

        assertTrue("an absent effort must not travel as null", !lastRequest().body.contains("reasoningEffort"))
    }

    @Test
    fun `an unavailable model surfaces its own code`() = runBlocking {
        stub("/mobile/models", 409, """{"error":{"code":"session/model-unavailable","message":"no key"}}""")
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.selectModel("session-a", "glm", "glm-5.3")
            fail("a model the host cannot use must be reported")
        } catch (error: ApiException) {
            assertEquals("session/model-unavailable", error.code)
            assertEquals(409, error.status)
        }
    }

    @Test
    fun `the pending approvals decode their prompt and options`() = runBlocking {
        stub(
            "/mobile/approvals",
            200,
            """
            {"items":[{"id":"approval-1","sessionId":"session-a","toolName":"pwsh","callId":"call-7",
                       "reason":"run a shell command","displayReason":"Esegue comandi",
                       "createdAt":1760000000000,"outcomes":["allowed-once","rejected"]}]}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val approval = api.approvals().single()

        assertEquals("pwsh", approval.toolName)
        assertEquals("call-7", approval.callId)
        assertEquals("Esegue comandi", approval.prompt)
        assertTrue(approval.outcomes.contains("allowed-once"))
    }

    @Test
    fun `an approval without a host sentence falls back to the audit reason`() = runBlocking {
        stub(
            "/mobile/approvals",
            200,
            """{"items":[{"id":"a","sessionId":"s","toolName":"write","reason":"write a file"}]}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        assertEquals("write a file", api.approvals().single().prompt)
    }

    @Test
    fun `answering an approval posts the outcome to its own route`() = runBlocking {
        stub(
            "/mobile/approvals/approval%2F1",
            200,
            """{"resolved":true,"approvalId":"approval/1","outcome":"allowed-once"}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val answer = api.answerApproval("approval/1", "allowed-once")

        assertEquals("POST", lastRequest().method)
        assertTrue("the id is path-encoded", lastRequest().path.contains("approval%2F1"))
        assertTrue(lastRequest().body.contains(""""outcome":"allowed-once""""))
        assertTrue(answer.resolved)
        assertEquals("allowed-once", answer.outcome)
    }

    @Test
    fun `losing the approval race surfaces as a conflict, not a failure`() = runBlocking {
        stub("/mobile/approvals/approval-1", 409, """{"error":{"code":"approval/settled","message":"already settled"}}""")
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.answerApproval("approval-1", "rejected")
            fail("a settled approval must be reported")
        } catch (error: ApiException) {
            assertEquals("approval/settled", error.code)
            assertEquals(409, error.status)
        }
    }

    @Test
    fun `the permission catalog decodes its presets and the session's own`() = runBlocking {
        stub(
            "/mobile/permissions",
            200,
            """
            {"sessionId":"session-a","current":"danger-full-access","defaultPreset":"workspace-write",
             "options":[{"value":"read-only","name":"Read Only","description":"Read files.",
                         "sandbox":"read-only","approval":"ask"},
                        {"value":"danger-full-access","name":"Full access","description":"No prompts.",
                         "sandbox":"danger-full-access","approval":"never"}]}
            """.trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val catalog = api.permissions("session-a")

        assertEquals("session-a", catalog.sessionId)
        assertEquals("danger-full-access", catalog.current)
        assertEquals("workspace-write", catalog.defaultPreset)
        assertEquals(2, catalog.options.size)
        val full = catalog.optionFor("danger-full-access")!!
        assertEquals("danger-full-access", full.sandbox)
        assertTrue("the never-ask policy has to survive the wire", full.grantsWithoutAsking)
    }

    @Test
    fun `the permission read names the session in the query`() = runBlocking {
        stub("/mobile/permissions", 200, """{"options":[]}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.permissions("session/a b")

        assertEquals("GET", lastRequest().method)
        // The wire form is whatever the client's HTTP layer canonicalizes to;
        // what must hold is what the host reads back. `URLSearchParams` decodes
        // `%20` as a space and leaves a bare `+` alone, so the value has to
        // round-trip under exactly that rule.
        val query = lastRequest().query ?: ""
        assertEquals("session/a b", percentDecode(query.substringAfter("sessionId=", "")))
    }

    @Test
    fun `a preview asks the host by address`() = runBlocking {
        stub("/mobile/preview", 200, """{"url":"https://example.com/a b","kind":"html","title":"Pagina","text":"parole"}""")
        val api = MobileApi(baseUrl, "tok-abc")

        val preview = api.preview("https://example.com/a b")

        assertEquals("GET", lastRequest().method)
        val query = lastRequest().query ?: ""
        assertTrue(query.startsWith("url="))
        assertFalse("a form-encoded '+' would read back as a space on the host", query.contains('+'))
        assertEquals("https://example.com/a b", percentDecode(query.removePrefix("url=")))
        assertEquals("Pagina", preview.title)
        assertEquals("html", preview.kind)
        assertEquals("parole", preview.text)
        assertTrue(preview.fromHtml)
    }

    @Test
    fun `a plus sign in a previewed address survives the round trip`() = runBlocking {
        stub("/mobile/preview", 200, """{"url":"https://example.com/?q=a+b","kind":"text","text":"x"}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.preview("https://example.com/?q=a+b")

        assertEquals("https://example.com/?q=a+b", percentDecode((lastRequest().query ?: "").removePrefix("url=")))
    }

    @Test
    fun `a preview that the host refuses keeps its own code`() = runBlocking {
        stub(
            "/mobile/preview",
            403,
            """{"error":{"code":"preview/blocked","message":"URL hostname resolves to a non-public IP address"}}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.preview("https://intranet.local/x")
            fail("a blocked address must be reported")
        } catch (error: ApiException) {
            assertEquals("preview/blocked", error.code)
            assertEquals(403, error.status)
        }
    }

    @Test
    fun `a preview served without a title decodes with none`() = runBlocking {
        stub("/mobile/preview", 200, """{"url":"https://example.com/n","kind":"text","text":"riga"}""")
        val api = MobileApi(baseUrl, "tok-abc")

        val preview = api.preview("https://example.com/n")

        assertNull(preview.title)
        assertFalse(preview.fromHtml)
    }

    @Test
    fun `a linked file carries its reference and the chat it belongs to`() = runBlocking {
        stub(
            "/mobile/file",
            200,
            """{"path":"K:\\Repository\\dsh-mobile-connector\\LORE-bozza.md","name":"LORE-bozza.md",
                "bytes":120,"text":"# LORE\n\nbozza\n","truncated":false}""".trimIndent(),
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val file = api.file("LORE-bozza.md", "session-aaaa")

        assertEquals("GET", lastRequest().method)
        val query = lastRequest().query ?: ""
        assertTrue("the reference travels verbatim", query.contains("ref=LORE-bozza.md"))
        assertTrue("a relative reference is resolved against the chat on screen", query.contains("session=session-aaaa"))
        assertEquals("K:\\Repository\\dsh-mobile-connector\\LORE-bozza.md", file.path)
        assertEquals("LORE-bozza.md", file.name)
        assertEquals(120L, file.bytes)
        assertEquals("# LORE\n\nbozza\n", file.text)
        assertFalse(file.truncated)
        assertTrue("a .md file is rendered, not shown as source", file.isMarkdown)
    }

    @Test
    fun `a Windows path and a space in a session id survive the round trip`() = runBlocking {
        stub("/mobile/file", 200, """{"path":"K:\\DSH Mobile\\a b.md","name":"a b.md","bytes":1,"text":"x"}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.file("K:\\DSH Mobile\\a b.md", "session/a b")

        val query = lastRequest().query ?: ""
        assertFalse("a form-encoded '+' would read back as a space on the host", query.contains('+'))
        assertEquals("K:\\DSH Mobile\\a b.md", percentDecode(query.substringAfter("ref=").substringBefore('&')))
        assertEquals("session/a b", percentDecode(query.substringAfter("session=")))
    }

    @Test
    fun `a file with no chat open asks without a session`() = runBlocking {        stub("/mobile/file", 200, """{"path":"C:\\x.md","name":"x.md","bytes":1,"text":"x"}""")
        val api = MobileApi(baseUrl, "tok-abc")

        api.file("C:\\x.md", null)

        val query = lastRequest().query ?: ""
        assertTrue(query.startsWith("ref="))
        assertFalse("an absolute reference needs no chat to resolve against", query.contains("session="))
    }

    @Test
    fun `a file that is not text keeps the host's own code`() = runBlocking {
        stub(
            "/mobile/file",
            415,
            """{"error":{"code":"file/binary","message":"logo.png is not text (2048 bytes)"}}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.file("logo.png", "session-aaaa")
            fail("a binary file must be reported rather than decoded")
        } catch (error: ApiException) {
            assertEquals("file/binary", error.code)
            assertEquals(415, error.status)
        }
    }

    @Test
    fun `a file link that named a line keeps it`() = runBlocking {
        // `[riga 35](LORE-bozza.md#L35)` is how an agent points inside a file: the
        // fragment is not part of the name, and dropping it in silence would be
        // honouring half the link.
        stub(
            "/mobile/file",
            200,
            """{"path":"K:\\Repository\\LORE-bozza.md","name":"LORE-bozza.md","bytes":10,"text":"x","line":35}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val file = api.file("LORE-bozza.md#L35", "session-aaaa")

        assertEquals(35, file.line)
        assertEquals("LORE-bozza.md", file.name)
    }

    @Test
    fun `a file with no line in the answer decodes with none`() = runBlocking {
        stub("/mobile/file", 200, """{"path":"C:\\x.md","name":"x.md","bytes":1,"text":"x","line":null}""")
        val api = MobileApi(baseUrl, "tok-abc")

        assertNull(api.file("C:\\x.md", null).line)
    }

    @Test
    fun `a harness older than the file route answers not-found`() = runBlocking {
        // Nothing is stubbed for `/mobile/file`: the plugin that predates the
        // route answers the generic 404, which the app turns into the one useful
        // sentence — restart the harness so it reads the updated plugin.
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.file("LORE-bozza.md", "session-aaaa")
            fail("a missing route must surface")
        } catch (error: ApiException) {
            assertEquals("not-found", error.code)
            assertEquals(404, error.status)
        }
    }

    @Test
    fun `a delivery is asked for by its chat, its event and its index`() = runBlocking {
        // The coordinates are the whole contract: the host resolves the *path*
        // out of its own log, so a phone cannot ask for a file no agent declared.
        stub(
            "/mobile/delivery",
            200,
            """{"path":"K:\\Repository\\Acme\\Bilancio 2026.xlsx","name":"Bilancio 2026.xlsx","bytes":4096,""" +
                """"text":"","truncated":false,"binary":true,"description":"il foglio dei conti"}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        val file = api.delivery("session-aaaa", 8, 2)

        assertEquals("Bilancio 2026.xlsx", file.name)
        assertEquals(4096L, file.bytes)
        assertTrue("a spreadsheet has no text to show here", file.binary)
        assertFalse(file.readable)
        assertEquals("il foglio dei conti", file.description)
        val sent = lastRequest()
        assertEquals("GET", sent.method)
        assertEquals("/mobile/delivery", sent.path)
        assertEquals("Bearer tok-abc", sent.authorization)
        assertEquals("session-aaaa", queryValue(sent.query, "session"))
        assertEquals("8", queryValue(sent.query, "seq"))
        assertEquals("2", queryValue(sent.query, "index"))
        assertNull("the description answer is not the raw one", queryValue(sent.query, "raw"))
    }

    @Test
    fun `a delivery download streams the bytes into the caller's stream`() = runBlocking {
        // Streamed rather than buffered: the phone's only reason to hold a whole
        // delivered file is to write it somewhere, and the two places it goes —
        // the Download folder and another app — both take a stream.
        stub("/mobile/delivery", 200, "PDF-BYTES")
        val api = MobileApi(baseUrl, "tok-abc")

        val written = java.io.ByteArrayOutputStream()
        api.downloadDelivery("session-aaaa", 8, 2) { it.copyTo(written) }

        assertEquals("PDF-BYTES", written.toString(Charsets.UTF_8.name()))
        val sent = lastRequest()
        assertEquals("/mobile/delivery", sent.path)
        assertEquals("1", queryValue(sent.query, "raw"))
        assertEquals("Bearer tok-abc", sent.authorization)
    }

    @Test
    fun `a refused download keeps the host's own code and sentence`() = runBlocking {
        stub(
            "/mobile/delivery",
            413,
            """{"error":{"code":"file/too-large","message":"annual.zip is 90000000 bytes, past the bound"}}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.downloadDelivery("session-aaaa", 8, 2) { it.readBytes() }
            fail("an oversize delivery must be refused before the phone stores it")
        } catch (error: ApiException) {
            assertEquals("file/too-large", error.code)
            assertEquals(413, error.status)
            assertTrue("the host's own sentence is what the app shows", error.message.contains("90000000"))
        }
    }

    @Test
    fun `a delivery the log no longer reaches surfaces as its own code`() = runBlocking {
        stub(
            "/mobile/delivery",
            404,
            """{"error":{"code":"delivery/not-found","message":"chat \"s\" declares no delivered file at seq 1"}}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.delivery("s", 1, 0)
            fail("a delivery that is not in the log must surface")
        } catch (error: ApiException) {
            assertEquals("delivery/not-found", error.code)
        }
    }

    @Test
    fun `only markdown names are rendered as markdown`() {
        assertTrue(HostFile(name = "nota.md").isMarkdown)
        assertTrue(HostFile(name = "NOTA.MD").isMarkdown)
        assertTrue(HostFile(name = "nota.markdown").isMarkdown)
        assertFalse("a text file keeps its own characters", HostFile(name = "nota.txt").isMarkdown)
        assertFalse("a source file is read, not reinterpreted", HostFile(name = "Main.kt").isMarkdown)
        assertFalse(HostFile(name = "Makefile").isMarkdown)
        assertFalse(HostFile(name = "").isMarkdown)
    }

    @Test
    fun `switching a preset posts the session and the value`() = runBlocking {
        stub("/mobile/permissions", 200, """{"sessionId":"session-a","current":"read-only"}""")
        val api = MobileApi(baseUrl, "tok-abc")

        val selection = api.selectPermission("session-a", "read-only")

        assertEquals("POST", lastRequest().method)
        assertTrue(lastRequest().body.contains(""""sessionId":"session-a""""))
        assertTrue(lastRequest().body.contains(""""preset":"read-only""""))
        assertEquals("read-only", selection.current)
    }

    @Test
    fun `a refused switch keeps the host's own sentence`() = runBlocking {
        stub(
            "/mobile/permissions",
            409,
            """{"error":{"code":"permission/refused","message":"Auto is not available in this session"}}""",
        )
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.selectPermission("session-a", "auto")
            fail("a refused switch must be reported")
        } catch (error: ApiException) {
            assertEquals("permission/refused", error.code)
            assertEquals("Auto is not available in this session", error.message)
        }
    }

    @Test
    fun `a harness without permission presets reports its own code`() = runBlocking {
        stub("/mobile/permissions", 503, """{"error":{"code":"permission/unavailable","message":"no presets"}}""")
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.permissions(null)
            fail("a host without presets must be reported")
        } catch (error: ApiException) {
            assertEquals("permission/unavailable", error.code)
        }
    }

    @Test
    fun `a host without the model routes reports it instead of failing silently`() = runBlocking {
        stub("/mobile/models", 404, """{"error":{"code":"not-found","message":"unknown route"}}""")
        val api = MobileApi(baseUrl, "tok-abc")

        try {
            api.models()
            fail("an older host must be reported, not rendered as an empty picker")
        } catch (error: ApiException) {
            assertEquals("not-found", error.code)
        }
    }
}

/** Contract tests for the image signature sniffer. */
class ImageSniffingTest {
    @Test
    fun `detects the four supported formats`() {
        assertEquals("image/png", ImageSniffing.sniff(png()))
        assertEquals("image/jpeg", ImageSniffing.sniff(jpeg()))
        assertEquals("image/gif", ImageSniffing.sniff(gif()))
        assertEquals("image/webp", ImageSniffing.sniff(webp()))
    }

    @Test
    fun `refuses anything else`() {
        assertNull("plain text is not an image", ImageSniffing.sniff("hello world, definitely not a photo".toByteArray()))
        assertNull("a truncated file is refused", ImageSniffing.sniff(byteArrayOf(0x89.toByte(), 0x50)))
        assertNull("an empty file is refused", ImageSniffing.sniff(ByteArray(0)))
        assertNull("a PDF is refused", ImageSniffing.sniff("%PDF-1.7 padpadpad".toByteArray()))
    }

    @Test
    fun `the supported set matches what the sniffer can return`() {
        assertEquals(4, ImageSniffing.SUPPORTED.size)
        assertTrue(ImageSniffing.SUPPORTED.contains(ImageSniffing.sniff(png())))
    }

    private fun png() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D,
    )

    private fun jpeg() = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
    )

    private fun gif() = "GIF89a............".toByteArray()

    private fun webp(): ByteArray {
        val bytes = ByteArray(16)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        return bytes
    }
}
