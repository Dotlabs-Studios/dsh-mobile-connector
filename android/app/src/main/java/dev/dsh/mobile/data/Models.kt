package dev.dsh.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One paired harness session as the mobile API reports it. */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val cwd: String? = null,
    /** The PC project this chat belongs to, when the host groups it into one. */
    @SerialName("workspaceId") val workspaceId: String? = null,
    @SerialName("workspaceTitle") val workspaceTitle: String? = null,
    val running: Boolean = false,
    @SerialName("agentAvailable") val agentAvailable: Boolean = false,
    val blank: Boolean = false,
    @SerialName("updatedAt") val updatedAt: Long = 0L,
    @SerialName("lastPromptAt") val lastPromptAt: Long? = null,
    @SerialName("parentSessionId") val parentSessionId: String? = null,
    val origin: String? = null,
    /**
     * What the last turn of this chat did, as the host reported it.
     *
     * Kept as the wire word rather than as the enum so that a harness which
     * invents a status this build has never heard of still decodes: the row then
     * simply shows no dot, which is the one rendering that claims nothing.
     */
    @SerialName("status") val statusWire: String? = null,
) {
    /** Title shown in the list, falling back to the workspace folder name. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: lastSegment(cwd)
            ?: id

    /** The status to draw, or null when the host said nothing this build understands. */
    val status: ChatStatus?
        get() = ChatStatus.fromWire(statusWire)
}

/** One PC project as the harness registry reports it. */
@Serializable
data class Workspace(
    @SerialName("workspaceId") val workspaceId: String,
    val path: String = "",
    val title: String = "",
    @SerialName("sessionIds") val sessionIds: List<String> = emptyList(),
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
) {
    /** Name shown in the picker, falling back to the folder's own name. */
    val displayTitle: String
        get() = title.takeIf { it.isNotBlank() } ?: lastSegment(path) ?: workspaceId
}

/** The workspace list response. */
@Serializable
data class WorkspaceListResponse(val items: List<Workspace> = emptyList())

/** One device the harness has paired. */
@Serializable
data class PairedDevice(
    /** Stable id the host derives from the token; what a revoke names. */
    val id: String = "",
    val name: String = "",
    @SerialName("createdAt") val createdAt: Long = 0L,
    @SerialName("lastSeenAt") val lastSeenAt: Long = 0L,
    /** True for the device making the request. */
    val current: Boolean = false,
) {
    /** The label to draw, falling back to the id for a device that reported none. */
    val displayName: String
        get() = name.takeIf { it.isNotBlank() } ?: id
}

/** The paired-device list response. */
@Serializable
data class DeviceListResponse(val items: List<PairedDevice> = emptyList())

/** The answer to revoking one device; `self` when the caller revoked itself. */
@Serializable
data class DeviceRevoked(
    val revoked: Boolean = false,
    val id: String = "",
    val self: Boolean = false,
)

/** The response to adopting a host directory as a project. */
@Serializable
data class WorkspaceCreated(
    @SerialName("workspaceId") val workspaceId: String,
    val path: String = "",
    val title: String = "",
    @SerialName("sessionIds") val sessionIds: List<String> = emptyList(),
    /** False when the directory already had a project: adopting is idempotent. */
    val created: Boolean = false,
)

/** The receipt of removing one project: the host's confirmation, and nothing deleted. */
@Serializable
data class WorkspaceDeleted(
    @SerialName("workspaceId") val workspaceId: String = "",
    val deleted: Boolean = false,
)

/**
 * One directory row of a browse level.
 *
 * [path] is absolute and host-owned: the app never joins segments itself,
 * because the phone cannot know which separator or drive the PC uses.
 */
@Serializable
data class DirEntry(
    val name: String = "",
    val path: String = "",
    val hidden: Boolean = false,
    /** True for a volume root, which the host offers as a shortcut. */
    val drive: Boolean = false,
)

/** One listed directory level of the PC's filesystem. */
@Serializable
data class DirListing(
    val path: String = "",
    /** The host account's home directory, offered as a shortcut. */
    val home: String = "",
    /** Ancestors of [path], root first; each one is a jump target. */
    val crumbs: List<DirEntry> = emptyList(),
    /** Child directories of [path], name-sorted. */
    val entries: List<DirEntry> = emptyList(),
    /** True when the host cut the level at its entry bound. */
    val truncated: Boolean = false,
    /** Set only by a create: the absolute path the host just made. */
    val created: String? = null,
)

/** One content block inside a message or tool result. */
@Serializable
data class Block(
    val type: String,
    val text: String? = null,
    @SerialName("attachmentId") val attachmentId: String? = null,
    @SerialName("mediaType") val mediaType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val name: String? = null,
    val bytes: Long? = null,
    val arguments: String? = null,
)

/** The final path segment of a host path, whichever separator it uses. */
internal fun lastSegment(path: String?): String? = path
    ?.trimEnd('\\', '/')
    ?.replace('\\', '/')
    ?.substringAfterLast('/')
    ?.takeIf { it.isNotBlank() }

/** One ordered session-log entry rendered for the chat transcript. */
@Serializable
data class Entry(
    val kind: String,
    val seq: Int,
    val type: String,
    val role: String? = null,
    val time: Long? = null,
    val turn: Int? = null,
    val step: Int? = null,
    val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val reason: String? = null,
    val interrupted: Boolean? = null,
    /**
     * Who produced the entry. The harness records `user` for the operator's own
     * prompts and its own provenance (`system-prompt`, `runtime-context`,
     * `skill-catalog`, `model`, …) for everything it composed or injected.
     */
    val source: String? = null,
    val isError: Boolean? = null,
    /** Set on an approval entry: the id the harness gave that approval. */
    val approvalId: String? = null,
    /** Set on a decided approval: the verdict the harness recorded. */
    val outcome: String? = null,
    /**
     * Set on a delivery entry: the files the agent declared with `present`.
     *
     * A delivery is its own durable event rather than the arguments of the call
     * that produced it, so it survives a phone that paged past the call — and it
     * is the only place the *declaration* lives, which is what the host resolves
     * when the file is opened or downloaded.
     */
    val files: List<DeclaredFile> = emptyList(),
    val content: List<Block> = emptyList(),
) {
    /** Whether this entry contributes a visible chat bubble. */
    val isMessage: Boolean get() = kind == "message"
}

/**
 * One file an agent declared as delivered.
 *
 * [index] is the file's position in its event's own array, and it is sent back
 * to the host verbatim when the file is read or downloaded: the host resolves
 * the declaration by it, so renumbering the list here would ask for a different
 * file. [path] is the declaration as the agent wrote it — often relative, and
 * meaningful next to the chat that printed it.
 */
@Serializable
data class DeclaredFile(
    val index: Int = 0,
    val path: String = "",
    val description: String? = null,
) {
    /** The name a card shows, taken from the last segment of the declared path. */
    val name: String get() = lastSegment(path) ?: path
}

/** One page of history for a session. */
@Serializable
data class MessagePage(
    @SerialName("sessionId") val sessionId: String,
    val cursor: Int = -1,
    val hasMore: Boolean = false,
    /** Where older history continues, present even when this page is empty. */
    val nextBeforeSeq: Int? = null,
    val entries: List<Entry> = emptyList(),
)

/** The session list response. */
@Serializable
data class SessionListResponse(val items: List<SessionSummary> = emptyList())

/** The pairing response. */
@Serializable
data class PairResponse(
    val token: String,
    val device: String = "",
    val reused: Boolean = false,
)

/** The host status response. */
@Serializable
data class StatusResponse(
    val ok: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val devices: List<PairedDevice> = emptyList(),
    /** Whether the host still accepts a pairing code, i.e. no device is paired yet. */
    @SerialName("pairingOpen") val pairingOpen: Boolean = false,
    @SerialName("sessionCount") val sessionCount: Int = 0,
    @SerialName("liveCount") val liveCount: Int = 0,
    val version: Int = 0,
)

/** The response to creating a session on the host. */
@Serializable
data class CreateSessionResponse(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("agentPreset") val agentPreset: String? = null,
)

/** The error envelope the mobile API answers with. */
@Serializable
data class ErrorEnvelope(val error: ErrorBody = ErrorBody())

/** The error detail inside [ErrorEnvelope]. */
@Serializable
data class ErrorBody(
    val code: String = "unknown",
    val message: String = "",
)

/** One reasoning effort a model accepts. */
@Serializable
data class ReasoningEffort(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
)

/** The reasoning block of a model's catalog row. */
@Serializable
data class ReasoningInfo(
    val efforts: List<ReasoningEffort> = emptyList(),
    @SerialName("defaultEffort") val defaultEffort: String? = null,
)

/**
 * One model the host can answer with.
 *
 * [vision], [contextWindow] and [maxTokens] come from the harness's LLM
 * registry rather than from its picker feed, so they are nullable on purpose: a
 * model whose adapter cannot describe it stays selectable, and null means
 * "unknown" rather than "absent".
 */
@Serializable
data class ModelInfo(
    val provider: String = "",
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val reasoning: ReasoningInfo? = null,
    val vision: Boolean? = null,
    @SerialName("contextWindow") val contextWindow: Long? = null,
    @SerialName("maxTokens") val maxTokens: Long? = null,
) {
    /** What the picker shows, falling back to the wire id. */
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: id

    /** Whether the model accepts images, when the host could tell. */
    val acceptsImages: Boolean get() = vision == true
}

/** One provider section of the model picker. */
@Serializable
data class ModelGroup(
    val id: String = "",
    val name: String = "",
    val models: List<ModelInfo> = emptyList(),
) {
    /** The provider's label, falling back to its id. */
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: id
}

/** Which model, and which reasoning effort, a session is set to use. */
@Serializable
data class ModelSelection(
    val provider: String = "",
    val model: String = "",
    @SerialName("reasoningEffort") val reasoningEffort: String? = null,
) {
    /** `provider/model`, which is what a compact surface shows. */
    val route: String get() = if (provider.isBlank()) model else "$provider/$model"
}

/** One provider whose catalog could not be read. */
@Serializable
data class ProviderFailure(
    val id: String = "",
    val name: String = "",
    val message: String = "",
)

/** The model picker's whole payload. */
@Serializable
data class ModelCatalog(
    val default: ModelSelection? = null,
    @SerialName("routableProviders") val routableProviders: List<String> = emptyList(),
    val groups: List<ModelGroup> = emptyList(),
    val failures: List<ProviderFailure> = emptyList(),
) {
    /** Whether any provider actually offered a model. */
    val isEmpty: Boolean get() = groups.all { it.models.isEmpty() }
}

/** The answer to a model switch. */
@Serializable
data class SelectedModelResponse(val selected: ModelSelection = ModelSelection())

/**
 * One approval the harness is waiting on.
 *
 * The vocabulary is the harness's own and deliberately narrow: the only grant
 * is `allowed-once`. [displayReason] is the host's localized sentence already
 * flattened to one line; [reason] is the audit text.
 */
@Serializable
data class ApprovalRequest(
    val id: String,
    @SerialName("sessionId") val sessionId: String = "",
    @SerialName("toolName") val toolName: String = "",
    @SerialName("callId") val callId: String? = null,
    val reason: String? = null,
    @SerialName("displayReason") val displayReason: String? = null,
    @SerialName("createdAt") val createdAt: Long = 0L,
    val outcomes: List<String> = emptyList(),
) {
    /** The sentence shown on the card, preferring the host's own wording. */
    val prompt: String
        get() = displayReason?.takeIf { it.isNotBlank() } ?: reason?.takeIf { it.isNotBlank() } ?: ""
}

/** The pending-approval list response. */
@Serializable
data class ApprovalListResponse(val items: List<ApprovalRequest> = emptyList())

/** The receipt for answering one approval. */
@Serializable
data class ApprovalAnswer(
    val resolved: Boolean = false,
    @SerialName("approvalId") val approvalId: String = "",
    val outcome: String = "",
)
