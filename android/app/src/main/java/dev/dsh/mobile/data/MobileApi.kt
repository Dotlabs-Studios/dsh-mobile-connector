package dev.dsh.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A failure reported by the mobile API or the transport beneath it. */
class ApiException(
    val code: String,
    override val message: String,
    val status: Int = 0,
) : IOException(message)

/** What this client calls itself on the wire. */
private const val USER_AGENT = "dsh-mobile-android/0.1"

/**
 * The thin HTTP client for one harness host.
 *
 * The wire contract is deliberately plain JSON over OkHttp: the API has fewer
 * than ten routes, so a code-generation stack would cost more than it removes.
 */
class MobileApi(
    private val baseUrl: String,
    private val token: String?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * The client a file upload travels on.
     *
     * A whole file is one request, so the control plane's patience is the wrong
     * patience for it: 32 MB over a phone network can take minutes, and a
     * timeout that fires halfway through is worse than a slow upload. It shares
     * the dispatcher and connection pool of [client], so this is a budget, not
     * a second stack.
     */
    private val uploadClient = client.newBuilder()
        .callTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * The client a delivery download travels on.
     *
     * Same reasoning as the upload's patience, from the other side: a delivered
     * document comes *down* over the same phone network, and the control plane's
     * 45-second budget is the wrong one for a few megabytes over a weak link.
     * It shares the dispatcher and the connection pool, so it is a budget rather
     * than a second stack.
     */
    private val downloadClient = client.newBuilder()
        .callTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * Send one request and decode its JSON body.
     *
     * @param path request path, without the host.
     * @param method HTTP verb.
     * @param body pre-encoded JSON body, or null.
     * @param serializer decoder for the success payload.
     * @return the decoded payload.
     * @throws ApiException for a transport failure or a non-2xx answer.
     */
    private suspend inline fun <reified T> call(
        path: String,
        method: String = "GET",
        body: String? = null,
    ): T = exchange(
        client,
        Request.Builder()
            .url(baseUrl + path)
            .header("accept", "application/json")
            .header("user-agent", USER_AGENT)
            .apply { token?.let { header("authorization", "Bearer $it") } }
            .method(
                method,
                body?.toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build(),
    )

    /**
     * Run one prepared request and decode its JSON body.
     *
     * @param http the client to run it on, which is how an upload gets its own
     *   patience without the rest of the API getting it too.
     * @param request the request to send.
     * @return the decoded payload.
     * @throws ApiException for a transport failure or a non-2xx answer.
     */
    private suspend inline fun <reified T> exchange(http: OkHttpClient, request: Request): T = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (error: IOException) {
            throw ApiException("transport", error.message ?: "cannot reach $baseUrl")
        }

        response.use { received ->
            val text = received.body?.string().orEmpty()
            if (!received.isSuccessful) throw failure(received.code, text)
            if (text.isBlank()) {
                throw ApiException("empty", "il server ha risposto senza contenuto")
            }
            try {
                json.decodeFromString<T>(text)
            } catch (error: Exception) {
                throw ApiException("decode", "risposta non valida: ${error.message}")
            }
        }
    }

    /**
     * Translate one non-2xx answer into the failure the UI reasons about.
     *
     * The envelope is the host's, so a refusal keeps its own code and its own
     * sentence; a response that is not the envelope at all — a proxy's HTML page,
     * a truncated body — becomes the status, which is still the truth.
     *
     * @param status the HTTP status.
     * @param text the response body, already read.
     * @returns the failure to throw.
     */
    private fun failure(status: Int, text: String): ApiException {
        val decoded = runCatching { json.decodeFromString<ErrorEnvelope>(text) }.getOrNull()
        return ApiException(
            code = decoded?.error?.code ?: "http-$status",
            message = decoded?.error?.message?.takeIf { it.isNotBlank() }
                ?: "richiesta rifiutata ($status)",
            status = status,
        )
    }

    /**
     * Claim a pairing code and mint this device's token.
     *
     * @param code the code printed in the harness log.
     * @param deviceName a label shown on the harness side.
     * @return the issued device token.
     */
    suspend fun pair(code: String, deviceName: String): PairResponse = call(
        path = "/mobile/pair",
        method = "POST",
        body = buildJsonObject {
            put("code", code)
            put("device", deviceName)
        }.toString(),
    )

    /** Read host and pairing status for the paired device. */
    suspend fun status(): StatusResponse = call("/mobile/status")

    /**
     * List the sessions the harness knows, newest first.
     *
     * @param includeSubagents whether to include subagent child sessions.
     * @param query optional case-insensitive filter over title, path, and id.
     */
    suspend fun sessions(includeSubagents: Boolean = false, query: String = ""): List<SessionSummary> {
        val params = buildString {
            append("/mobile/sessions?subagents=")
            append(if (includeSubagents) "1" else "0")
            if (query.isNotBlank()) {
                append("&q=")
                append(java.net.URLEncoder.encode(query, "UTF-8"))
            }
        }
        return call<SessionListResponse>(params).items
    }

    /**
     * Create a new session on the host so the phone can start a fresh chat.
     *
     * @param cwd the host directory to run in, or null for the host's default.
     * @param workspaceId a project id, which takes precedence over [cwd]: the
     *   host then also records the chat in that project's account, which is what
     *   makes it appear in the desktop sidebar immediately.
     */
    suspend fun createSession(cwd: String? = null, workspaceId: String? = null): CreateSessionResponse {
        val payload = buildJsonObject {
            if (!workspaceId.isNullOrBlank()) put("workspaceId", workspaceId)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        }
        return call("/mobile/sessions", method = "POST", body = payload.toString())
    }

    /**
     * List the PC projects the harness groups chats into.
     *
     * The registry owns the order, and it is the order the desktop sidebar
     * shows, so the phone mirrors it instead of inventing one.
     */
    suspend fun workspaces(): List<Workspace> = call<WorkspaceListResponse>("/mobile/workspaces").items

    /**
     * Adopt one host directory as a project.
     *
     * Idempotent on the host: a directory that already has a project returns
     * that record with `created = false`, so a phone that lost track of the
     * list converges instead of creating duplicates.
     *
     * @param path an absolute directory the host reported.
     */
    suspend fun createWorkspace(path: String): WorkspaceCreated = call(
        path = "/mobile/workspaces",
        method = "POST",
        body = buildJsonObject { put("path", path) }.toString(),
    )

    /**
     * Remove one project from the host's list.
     *
     * Non-destructive by contract: the route unregisters the project and never
     * touches the folder, its files or the chat histories — the chats simply stop
     * being grouped. That is what makes it safe to offer from a list, and it is
     * also why the confirmation says so out loud (see [removeWorkspaceDetail]).
     *
     * @param workspaceId the project to remove, as the host named it.
     */
    suspend fun deleteWorkspace(workspaceId: String): WorkspaceDeleted = call(
        path = "/mobile/workspaces/${encodeQueryValue(workspaceId)}",
        method = "DELETE",
    )

    /**
     * List one directory level of the PC's filesystem.
     *
     * @param path an absolute host path, or null to start at the host account's
     *   home directory. The host answers with its own breadcrumb chain and
     *   volume roots, so the app never guesses a host path.
     */
    suspend fun browse(path: String? = null): DirListing {
        val query = if (path.isNullOrBlank()) "" else "?path=" + java.net.URLEncoder.encode(path, "UTF-8")
        return call("/mobile/dirs$query")
    }

    /**
     * Create one directory on the PC and read back its parent level.
     *
     * @param parent an absolute host directory that already exists.
     * @param name a single directory name, without separators.
     */
    suspend fun createDirectory(parent: String, name: String): DirListing = call(
        path = "/mobile/dirs",
        method = "POST",
        body = buildJsonObject {
            put("parent", parent)
            put("name", name)
        }.toString(),
    )

    /**
     * Read one page of history.
     *
     * @param sessionId the addressed session.
     * @param beforeSeq exclusive upper bound, or null for the newest page.
     * @param limit maximum entries to return.
     */
    suspend fun messages(sessionId: String, beforeSeq: Int? = null, limit: Int = 60): MessagePage {
        val params = buildString {
            append("/mobile/sessions/")
            append(java.net.URLEncoder.encode(sessionId, "UTF-8"))
            append("/messages?limit=")
            append(limit)
            if (beforeSeq != null) {
                append("&beforeSeq=")
                append(beforeSeq)
            }
        }
        return call(params)
    }

    /**
     * Send a prompt to a session.
     *
     * Photos travel inline as canonical base64 image parts: the harness admits
     * them as image content, so the model actually sees the picture. Sending a
     * pre-stored file reference instead would surface as an opaque file block.
     *
     * @param sessionId the target session.
     * @param text message text, or blank for a photo-only message.
     * @param images ordered base64 photos with their media types.
     * @param files files already staged on the host, named by their receipts.
     * @param steer true to steer the running turn instead of queueing.
     */
    suspend fun send(
        sessionId: String,
        text: String,
        images: List<OutgoingImage> = emptyList(),
        files: List<OutgoingFile> = emptyList(),
        steer: Boolean = false,
    ) {
        val payload = buildJsonObject {
            if (text.isNotBlank()) put("text", text)
            if (steer) put("mode", "steer")
            if (images.isNotEmpty()) {
                put(
                    "images",
                    JsonArray(
                        images.map { image ->
                            buildJsonObject {
                                put("mediaType", image.mediaType)
                                put("data", image.base64)
                                image.name?.let { put("name", it) }
                            }
                        },
                    ),
                )
            }
            if (files.isNotEmpty()) {
                // Only the receipt travels: the bytes and the name are already
                // the host's, and it is the host that resolves the receipt into
                // the stored reference the model is told about.
                put(
                    "files",
                    JsonArray(
                        files.map { file ->
                            buildJsonObject { put("receiptId", file.receiptId) }
                        },
                    ),
                )
            }
        }
        call<JsonObject>(
            path = "/mobile/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}/messages",
            method = "POST",
            body = payload.toString(),
        )
    }

    /**
     * Store one photo on the host and return its durable reference.
     *
     * @param base64 canonical base64 image bytes.
     * @param name display name.
     */
    suspend fun uploadImage(base64: String, name: String): UploadedImage = call(
        path = "/mobile/images",
        method = "POST",
        body = buildJsonObject {
            put("data", base64)
            put("name", name)
        }.toString(),
    )

    /**
     * Stage one file on the host and return the receipt a message names.
     *
     * The bytes are the request body: a generic file is stored verbatim, and an
     * envelope would only add a copy of it in memory on both sides. The receipt
     * belongs to one chat and is spent by the message that carries it, so this
     * is addressed to the session the file is being attached to — which is what
     * the composer already knows.
     *
     * @param sessionId the chat the file is staged for.
     * @param name the file's display name, as the picker reported it.
     * @param bytes the exact file bytes.
     */
    suspend fun uploadFile(sessionId: String, name: String, bytes: ByteArray): UploadedFile {
        val query = java.net.URLEncoder.encode(name, "UTF-8")
        val session = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/mobile/files?sessionId=$session&name=$query")
            .header("accept", "application/json")
            .header("user-agent", USER_AGENT)
            .apply { token?.let { header("authorization", "Bearer $it") } }
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return exchange(uploadClient, request)
    }

    /**
     * List the models the host can answer with.
     *
     * The host returns the same catalog its own desktop picker renders, so the
     * phone offers the models actually configured on that machine.
     */
    suspend fun models(): ModelCatalog = call("/mobile/models")

    /**
     * Point one session at a model from the next request onward.
     *
     * This is the call the desktop picker makes, including its side effect: the
     * harness records the choice as the deployment default too, which the UI
     * says out loud rather than hiding.
     *
     * @param sessionId the session to switch.
     * @param provider the provider id from the catalog.
     * @param model the model id from the catalog.
     * @param reasoningEffort the effort to use, or null to leave it to the host.
     */
    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): ModelSelection {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("provider", provider)
            put("model", model)
            if (!reasoningEffort.isNullOrBlank()) put("reasoningEffort", reasoningEffort)
        }
        return call<SelectedModelResponse>("/mobile/models", method = "POST", body = payload.toString()).selected
    }

    /**
     * List the approvals the host is waiting on.
     *
     * A phone polls rather than holding the event stream open, so this read is
     * what makes an approval card survive a reconnect, a backgrounded app, or a
     * frame that arrived while nobody was looking.
     */
    suspend fun approvals(): List<ApprovalRequest> = call<ApprovalListResponse>("/mobile/approvals").items

    /**
     * Answer one pending approval.
     *
     * @param approvalId the id from [approvals].
     * @param outcome `allowed-once` to grant it, `rejected` to refuse it.
     */
    suspend fun answerApproval(approvalId: String, outcome: String): ApprovalAnswer = call(
        path = "/mobile/approvals/${java.net.URLEncoder.encode(approvalId, "UTF-8")}",
        method = "POST",
        body = buildJsonObject { put("outcome", outcome) }.toString(),
    )

    /**
     * List the questions the host is waiting on.
     *
     * Includes questions whose live window has closed: the harness keeps those
     * answerable, and a reader who took three minutes should still be able to
     * reply.
     */
    suspend fun questions(): List<PendingQuestion> = call<QuestionListResponse>("/mobile/questions").items

    /**
     * Answer one question set.
     *
     * @param questionId the id from [questions].
     * @param answers one entry per question, keyed by the question's own id.
     */
    suspend fun answerQuestion(questionId: String, answers: List<QuestionAnswer>): QuestionAnswerResponse = call(
        path = "/mobile/questions/${java.net.URLEncoder.encode(questionId, "UTF-8")}",
        method = "POST",
        body = buildJsonObject {
            put(
                "answers",
                JsonArray(
                    answers.map { answer ->
                        buildJsonObject {
                            put("id", answer.id)
                            put("selected", JsonArray(answer.selected.map { JsonPrimitive(it) }))
                            answer.custom?.takeIf { it.isNotBlank() }?.let { put("custom", it) }
                        }
                    },
                ),
            )
        }.toString(),
    )

    /**
     * Read the permission presets this harness offers, and where one session stands.
     *
     * @param sessionId the session to report the current preset for; the catalog
     *   itself is process-wide and comes back either way.
     */
    suspend fun permissions(sessionId: String?): PermissionCatalog = call(
        path = if (sessionId == null) {
            "/mobile/permissions"
        } else {
            "/mobile/permissions?sessionId=${encodeQueryValue(sessionId)}"
        },
    )

    /**
     * Switch one session's permission preset.
     *
     * The value must be one the host offered: it bundles a sandbox mode with an
     * approval policy, and the route refuses anything that is not in its own
     * catalog.
     *
     * @param sessionId the session to switch.
     * @param preset the preset's `value` from [permissions].
     */
    suspend fun selectPermission(sessionId: String, preset: String): PermissionSelection = call(
        path = "/mobile/permissions",
        method = "POST",
        body = buildJsonObject {
            put("sessionId", sessionId)
            put("preset", preset)
        }.toString(),
    )

    /**
     * Preview one link on the host and read it back.
     *
     * The fetch is the host's, under the harness's own web policy rather than
     * this phone's network — which is why nothing here ever loads the page
     * client-side.
     *
     * @param url the link to open.
     */
    suspend fun preview(url: String): PreviewResponse = call(
        path = "/mobile/preview?url=${encodeQueryValue(url)}",
    )

    /**
     * Read one file off the PC and read it back as text.
     *
     * The reference travels exactly as the agent wrote it. A relative one is the
     * host's to resolve, against the working directory of the chat it was printed
     * in — which is why the session goes along with it: a phone that joined path
     * segments itself would be guessing at separators, drives and case rules it
     * cannot see, and `LORE-bozza.md` means the file beside *that chat*, not
     * beside whatever directory the host process happens to sit in.
     *
     * @param reference an absolute path, a `file:` URL, or a path relative to [sessionId].
     * @param sessionId the chat on screen, or null when none is open.
     */
    suspend fun file(reference: String, sessionId: String?): HostFile {
        val chat = sessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "&session=${encodeQueryValue(it)}" }
            .orEmpty()
        return call(path = "/mobile/file?ref=${encodeQueryValue(reference)}$chat")
    }

    /**
     * Ask the host what one delivered file is.
     *
     * The address is the delivery's own coordinates — the chat, the sequence of
     * the `deliverables/presented` event, the file's index in it — and not a path.
     * That is what keeps the *declaration* the host's: it resolves the path out of
     * its own log, so this app cannot ask for a file no agent handed over, and an
     * old entry still resolves to what the log says now. A relative declaration
     * comes back resolved against the chat that made it.
     *
     * @param sessionId the chat the delivery belongs to.
     * @param seq the sequence of the delivery event.
     * @param index the file's position in that event.
     */
    suspend fun delivery(sessionId: String, seq: Int, index: Int): DeliveredFile =
        call(deliveryPath(sessionId, seq, index))

    /**
     * Stream one delivered file's exact bytes into [sink].
     *
     * The bytes go straight to the caller's stream rather than through a `ByteArray`:
     * a delivered file can be megabytes, and the phone has exactly one reason to
     * hold all of it — writing it somewhere.
     *
     * @param sessionId the chat the delivery belongs to.
     * @param seq the sequence of the delivery event.
     * @param index the file's position in that event.
     * @param sink receives the response body, already positioned at its first byte.
     */
    suspend fun downloadDelivery(
        sessionId: String,
        seq: Int,
        index: Int,
        sink: (java.io.InputStream) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl${deliveryPath(sessionId, seq, index)}&raw=1")
            .header("accept", "application/octet-stream")
            .header("user-agent", USER_AGENT)
            .apply { token?.let { header("authorization", "Bearer $it") } }
            .get()
            .build()
        val response = try {
            downloadClient.newCall(request).execute()
        } catch (error: IOException) {
            throw ApiException("transport", error.message ?: "cannot reach $baseUrl")
        }
        response.use { received ->
            if (!received.isSuccessful) throw failure(received.code, received.body?.string().orEmpty())
            val body = received.body ?: throw ApiException("empty", "il server ha risposto senza contenuto")
            body.byteStream().use(sink)
        }
    }

    /**
     * The route one delivered file's bytes live at.
     *
     * @param sessionId the chat the delivery belongs to.
     * @param seq the sequence of the delivery event.
     * @param index the file's position in that event.
     */
    private fun deliveryPath(sessionId: String, seq: Int, index: Int): String =
        "/mobile/delivery?session=${encodeQueryValue(sessionId)}&seq=$seq&index=$index"

    /**
     * Build the URL that streams one stored image as raw bytes.
     *
     * @param sessionId an authorized session that references the image.
     * @param attachmentId the durable attachment id.
     */
    fun imageUrl(sessionId: String, attachmentId: String): String {
        val encodedSession = java.net.URLEncoder.encode(sessionId, "UTF-8")
        val encodedAttachment = java.net.URLEncoder.encode(attachmentId, "UTF-8")
        return "$baseUrl/mobile/sessions/$encodedSession/images/$encodedAttachment/raw"
    }

    /** The bearer token, so an image loader can authorize its requests. */
    fun bearerToken(): String? = token

    /**
     * Probe whether the host answers and the token is still valid.
     *
     * @return true when status succeeds.
     */
    suspend fun probe(): Boolean = runCatching { status() }.isSuccess
}

/** A base64 photo queued for one outgoing message. */
data class OutgoingImage(val base64: String, val mediaType: String, val name: String? = null)

/**
 * Percent-encode one query-string value.
 *
 * `URLEncoder` is form encoding: it turns a space into `+`, and the host's
 * `URLSearchParams` reads `+` back as a plus sign. A link whose address carries
 * either character would arrive changed — so the value is encoded strictly, as a
 * URI query component.
 *
 * @param value the raw value.
 * @returns the encoded value.
 */
internal fun encodeQueryValue(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xff
        val unreserved = c in 0x41..0x5a || c in 0x61..0x7a || c in 0x30..0x39 ||
            c == 0x2d || c == 0x2e || c == 0x5f || c == 0x7e
        if (unreserved) append(c.toChar()) else append("%").append(c.toString(16).padStart(2, '0'))
    }
}

/** The reference returned after storing a photo on the host. */
@kotlinx.serialization.Serializable
data class UploadedImage(
    @kotlinx.serialization.SerialName("attachmentId") val attachmentId: String,
    val name: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
)
