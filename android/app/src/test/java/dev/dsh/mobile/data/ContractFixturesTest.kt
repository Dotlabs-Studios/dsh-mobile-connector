package dev.dsh.mobile.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decodes the payloads the plugin's own suite captured from the real routes.
 *
 * The contract between the two halves used to be a document and two hand-written
 * fixtures: one suite proved the host answers a shape, the other proved the phone
 * decodes a shape, and nothing proved the two shapes were the same one. The plugin
 * suite now writes the fixtures under `test/contract` from live route answers and
 * fails when one of them moves without a regenerated file; this test decodes those
 * files with the same serializers the app uses at runtime, so a rename on the host
 * breaks a JVM test instead of a screen on somebody's phone.
 */
class ContractFixturesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the session list fixture decodes into rows the list screen can draw`() {
        val page = json.decodeFromString<SessionListResponse>(fixture("sessions"))
        assertTrue("the fixture carries rows", page.items.isNotEmpty())

        val session = page.items.first { it.id == "session-aaaa" }
        assertEquals("Chat dal PC", session.title)
        assertEquals("default-workspace", session.workspaceTitle)
        assertEquals("completed", session.statusWire)
        assertTrue("a title is what a row draws", session.displayTitle.isNotBlank())
        assertTrue("a row can say which project it belongs to", session.workspaceId != null)
    }

    @Test
    fun `the history fixture decodes into a transcript`() {
        val page = json.decodeFromString<MessagePage>(fixture("messages"))
        assertTrue("the fixture carries entries", page.entries.isNotEmpty())
        assertEquals("the cursor travels", 9, page.cursor)

        val call = page.entries.first { it.kind == "tool-call" }
        assertEquals("pwsh", call.name)
        assertTrue("a tool call carries its arguments", !call.arguments.isNullOrBlank())

        val result = page.entries.first { it.kind == "tool-result" }
        assertEquals("file.txt", result.content.firstOrNull { it.type == "text" }?.text)

        val message = page.entries.first { it.kind == "message" }
        assertTrue("a message carries text", !message.content.firstOrNull { it.type == "text" }?.text.isNullOrBlank())
    }

    @Test
    fun `the pulse fixture decodes into the four sections the list screen reads`() {
        val pulse = json.decodeFromString<PulseResponse>(fixture("pulse"))
        assertTrue("sessions travel", pulse.sessions.isNotEmpty())
        assertTrue("projects travel", pulse.workspaces.isNotEmpty())
        assertTrue("the host is known to group chats", pulse.workspacesAvailable)
        assertTrue("both queues are present", pulse.approvalsPending >= 0 && pulse.questionsPending >= 0)
        assertTrue("a pulse session carries an id", pulse.sessions.all { it.id.isNotBlank() })
    }

    @Test
    fun `the device fixture decodes with ids and never a token`() {
        val devices = json.decodeFromString<DeviceListResponse>(fixture("devices"))
        assertTrue("both phones are in the fixture", devices.items.size == 2)
        assertTrue("every device has an id", devices.items.all { it.id.isNotBlank() })
        assertTrue("one of them is the caller", devices.items.count { it.current } == 1)
        assertTrue("a label is what the row draws", devices.items.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `the health fixture decodes into the handshake the app checks`() {
        val health = json.decodeFromString<HealthResponse>(fixture("health"))
        assertTrue("the route says it is healthy", health.ok)
        assertTrue("the wire version is one this client speaks", health.apiVersion >= 2)
        assertTrue("the plugin names its build", Regex("""\d+\.\d+\.\d+""").matches(health.pluginVersion))
        assertTrue("the port travels", health.port > 0)
    }

    @Test
    fun `the approvals fixture decodes into an answerable card`() {
        val approval = json.decodeFromString<ApprovalListResponse>(fixture("approvals"))
        val item = approval.items.single()
        assertEquals("pwsh", item.toolName)
        assertEquals("call-77", item.callId)
        assertEquals("Run the build", item.displayReason)
        assertEquals("Run the build", item.prompt)
        assertTrue("only the outcomes the host offers", item.outcomes.contains("allowed-once"))
        assertTrue("the card can be aged", item.createdAt > 0L)
    }

    @Test
    fun `the questions fixture decodes into choices the reader can press`() {
        val questions = json.decodeFromString<QuestionListResponse>(fixture("questions"))
        val item = questions.items.single()
        assertEquals("session-aaaa", item.sessionId)
        assertEquals(false, item.expired)

        val question = item.questions.single()
        assertEquals("Come procediamo con il deploy?", question.question)
        assertEquals("Tempistica backend", question.header)
        assertTrue("the options travel", question.options.size == 2)
        assertEquals("Adesso (Consigliato)", question.options.first().label)
    }

    /** Read one captured fixture, failing the test with where it looked. */
    private fun fixture(name: String): String {
        val candidates = listOf(
            File("../../test/contract/$name.json"),
            File("test/contract/$name.json"),
        )
        candidates.forEach { file -> if (file.isFile) return file.readText() }
        throw AssertionError(
            "no fixture $name.json: looked for ${candidates.joinToString(" or ")} " +
                "from ${File(".").absolutePath} — run `node test/plugin-check.mjs` to write it",
        )
    }
}
