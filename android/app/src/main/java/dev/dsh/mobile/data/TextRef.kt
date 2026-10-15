package dev.dsh.mobile.data

import android.content.Context
import androidx.annotation.StringRes

/**
 * A message that has been decided but not yet spelled.
 *
 * The app has two layers, and only one of them may know a language. Texts that
 * are *chosen* — which refusal an error code means, what a state is called —
 * belong to the layer that can be checked on the JVM without a phone, and that
 * layer must not reach for a `Context`; texts that are *drawn* belong to Compose,
 * where `stringResource` already resolves the language in force. This is the
 * handover between them: an id and the values that go in it, carried as data
 * until a composable is there to spell it.
 *
 * It is not a `String` in disguise. Nothing can be read out of a [Res] without a
 * resource table, which is the point: a hard-coded sentence cannot travel this
 * way, so every message that does is already translated. The one exception is
 * [Raw], and it exists for the one kind of text this app does not write —
 * whatever the host said when it failed. Passing that through is not a
 * translation gap; inventing a replacement for it would be worse than showing it,
 * because a wrong explanation reads as confidently as a right one.
 */
sealed interface TextRef {

    /**
     * A message this app wrote, named by its resource.
     *
     * @property id the string resource to draw.
     * @property args the values its placeholders take, positional.
     */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : TextRef

    /**
     * A message written by the host, shown as it arrived.
     *
     * @property text the host's own sentence.
     */
    data class Raw(val text: String) : TextRef

    companion object {
        /**
         * A message with no values in it.
         *
         * @param id the string resource to draw.
         */
        fun of(@StringRes id: Int): TextRef = Res(id)

        /**
         * A message whose placeholders are filled positionally.
         *
         * A null argument becomes the empty string rather than the word `null`:
         * these values come from a host that is free not to say them, and "the
         * file is no longer on the PC: null" is not a sentence.
         *
         * @param id the string resource to draw.
         * @param args the values, in the order the placeholders name them.
         */
        fun of(@StringRes id: Int, vararg args: Any?): TextRef =
            Res(id, args.map { it ?: "" })

        /**
         * The sentence a failure carried in its own words, when it carried any.
         *
         * A host error and a transport error both arrive with a `message`, and
         * this app shows it verbatim when it has nothing better to say: the
         * alternative is guessing at a cause from a code it does not know.
         *
         * @param error the failure.
         * @return the failure's own sentence, or null when it wrote none.
         */
        fun of(error: Throwable): TextRef? =
            error.message?.takeIf { it.isNotBlank() }?.let { Raw(it) }
    }
}

/**
 * The sentence, in the language in force.
 *
 * For the paths that have a `Context` and no composition — a notice written into
 * a map before any composable reads it, a banner assembled in a coroutine. Where
 * a composable is drawing, `textResource` is the one to call instead: it is the
 * same answer, and it re-reads itself when the language changes.
 *
 * @param context any context whose resources carry the chosen language.
 * @return the message.
 */
fun TextRef.resolve(context: Context): String = when (this) {
    is TextRef.Raw -> text
    is TextRef.Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
}

