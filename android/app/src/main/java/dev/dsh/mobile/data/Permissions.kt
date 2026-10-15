package dev.dsh.mobile.data

import kotlinx.serialization.Serializable

/**
 * One permission preset a session can be switched to.
 *
 * The harness bundles two knobs under one name: the sandbox mode, which is what
 * the agent may touch, and the approval policy, which is whether it has to ask
 * first. `danger-full-access` bundles "never ask", so a preset is not a
 * cosmetic choice — which is why the knobs travel with it and the app shows
 * them.
 *
 * @property value the name the harness resolves, and the only thing that may be
 *   sent back: the label is presentation and a deployment may word it freely.
 * @property name the host's own label for the preset.
 * @property description the host's own explanation, when it wrote one.
 * @property sandbox the sandbox mode this preset sets, when the host could
 *   resolve it; null means unknown, not permissive.
 * @property approval the approval policy this preset sets, when known.
 */
@Serializable
data class PermissionOption(
    val value: String = "",
    val name: String = "",
    val description: String? = null,
    val sandbox: String? = null,
    val approval: String? = null,
) {
    /**
     * Whether this preset tells the agent to stop asking.
     *
     * Read from the harness's own vocabulary rather than from the preset's name,
     * so a deployment that invents its own preset is judged by what it does.
     */
    val grantsWithoutAsking: Boolean get() = approval == "never"

    /** Whether the host could say what this preset does. */
    val knobsKnown: Boolean get() = sandbox != null || approval != null
}

/**
 * The permission presets this harness offers, and where the session stands.
 *
 * @property sessionId the session these values describe, or null when the client
 *   asked without naming one.
 * @property current the preset in force, or null when the host has none to
 *   report — a session it never pinned, or one it does not hold.
 * @property defaultPreset the preset fresh sessions will start with.
 * @property options the selectable presets, in the host's own order.
 */
@Serializable
data class PermissionCatalog(
    val sessionId: String? = null,
    val current: String? = null,
    val defaultPreset: String? = null,
    val options: List<PermissionOption> = emptyList(),
) {
    /**
     * The option a preset name refers to.
     *
     * @param value the preset name.
     * @returns the option, or null when the host does not offer it.
     */
    fun optionFor(value: String?): PermissionOption? =
        value?.let { wanted -> options.firstOrNull { it.value == wanted } }
}

/** The receipt for switching one session's preset. */
@Serializable
data class PermissionSelection(
    val sessionId: String = "",
    val current: String? = null,
)

/**
 * The presets this app has words of its own for.
 *
 * Which preset a value names is a decision, and it lives here, where it can be
 * checked on the JVM without a phone; the words for it live in the UI, which is
 * the only layer that knows the language in force. The host's labels are English
 * and this app's base language is Italian, so the three shipped presets — and
 * Auto, whose identity the harness fixes — are worded by the app's own resources
 * rather than by the host.
 *
 * [Other] is not a preset: it is the answer for a value this app has no words
 * for. A deployment that invented a preset knows better than this app what to
 * call it, so nothing here may spell one out — [hostLabel] hands back the host's
 * own label and [PermissionOption.description] its own explanation.
 */
enum class PermissionPreset {
    /** `read-only`: reads, and runs what does not write. */
    ReadOnly,

    /** `workspace-write`: writes inside the project and the temp folders. */
    WorkspaceWrite,

    /** `danger-full-access`: no boundary, and no asking either. */
    FullAccess,

    /** `auto`: a reviewer decides instead of the operator. */
    Auto,

    /** A value this app cannot word; the host's own words are used for it. */
    Other,
    ;

    companion object {
        /**
         * The preset one value names.
         *
         * The values are the harness's own vocabulary and are matched exactly: a
         * name is a protocol identifier, and guessing at a near miss would label
         * a session with a permission nobody granted. A value that is absent
         * names no preset either, so it lands on [Other] too — what to say about
         * "none" is the caller's business, not this mapping's.
         *
         * @param value the preset name the host resolves.
         * @returns the preset, or [Other] when this app has no words for it.
         */
        fun of(value: String?): PermissionPreset = when (value) {
            "read-only" -> ReadOnly
            "workspace-write" -> WorkspaceWrite
            "danger-full-access" -> FullAccess
            "auto" -> Auto
            else -> Other
        }
    }
}

/** The preset this option is, or [PermissionPreset.Other] when it has no words. */
val PermissionOption.preset: PermissionPreset get() = PermissionPreset.of(value)

/**
 * The host's own words for a preset this app cannot word.
 *
 * The label the harness sent, and — when it wrote none — the value it resolves:
 * a preset the app has no sentence for is still a permission somebody
 * configured, and printing its identifier is ugly but true. This is not a
 * resource, because it is another program's text and translating it would be
 * inventing one.
 */
val PermissionOption.hostLabel: String get() = name.ifBlank { value }
