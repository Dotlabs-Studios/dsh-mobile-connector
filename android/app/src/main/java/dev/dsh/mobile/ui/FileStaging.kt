package dev.dsh.mobile.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.R
import dev.dsh.mobile.data.MAX_ATTACHMENT_BYTES
import dev.dsh.mobile.data.TextRef
import dev.dsh.mobile.data.attachmentName
import dev.dsh.mobile.data.attachmentRefusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Read a picked file and stage it on the host.
 *
 * A file is uploaded as soon as it is picked, not when the message is sent: its
 * bytes are committed by the host, and the message then only has to name what
 * was committed. That is the order the harness itself requires — a `file` part
 * carries a receipt, and a receipt exists only after the bytes are stored — and
 * it is also the kinder one on a phone: the send stays instant, and a file that
 * fails to upload fails at the moment the user picked it, next to the composer
 * that still holds the message they were writing.
 *
 * A refusal is chosen here and spelled where the reader is: staging runs on an
 * IO thread with no resource table in reach, so it travels as a [TextRef] — the
 * name of a sentence, never the sentence. The size cap is the one refusal that
 * is worded elsewhere: it arrives already decided, from `attachmentRefusal`.
 *
 * @param context a context able to open the content URI.
 * @param uri the picked file.
 */
fun AppViewModel.stageFileFrom(context: Context, uri: Uri) {
    viewModelScope.launch {
        val payload = withContext(Dispatchers.IO) {
            runCatching {
                val picked = pickerFile(context, uri)
                // Refuse on the size the provider reports *before* reading: a
                // 2 GB video must not be pulled into memory to be turned down.
                val reported = picked.second
                if (reported != null) attachmentRefusal(reported)?.let { throw RejectedFile(it) }
                val bytes = context.contentResolver.openInputStream(uri)
                    ?.use { readBounded(it, MAX_ATTACHMENT_BYTES) }
                    ?: throw RejectedFile(TextRef.of(R.string.staging_pick_unreadable))
                attachmentName(picked.first) to bytes
            }
        }
        payload.onSuccess { (name, bytes) ->
            attachmentRefusal(bytes.size.toLong())?.let { refusal ->
                onFileRejected(refusal)
                return@onSuccess
            }
            stageFile(name, bytes)
        }.onFailure { error ->
            onFileRejected(
                when (error) {
                    is RejectedFile -> error.refusal
                    // A provider or a filesystem failing in its own words: there
                    // is nothing to translate, and its sentence is more use than
                    // a generic one this app would have to invent.
                    else -> TextRef.of(error) ?: TextRef.of(R.string.staging_file_unreadable)
                },
            )
        }
    }
}

/**
 * A file refused before or while it was read, carrying the reason to show.
 *
 * The reason is a [TextRef] rather than the exception's own message because it
 * is read by the user and not by a developer: the banner spells it, in the
 * language in force, and this exception carries no sentence of its own.
 *
 * @property refusal the sentence, decided but not spelled.
 */
private class RejectedFile(val refusal: TextRef) : IOException()

/**
 * Read at most [limit] bytes.
 *
 * A provider that does not report a size — a stream, a cloud document — would
 * otherwise be read until the phone ran out of memory, and the cap has to hold
 * for the files nobody measured as much as for the ones they did.
 *
 * @param stream the picked file's bytes.
 * @param limit maximum accepted byte count.
 * @throws RejectedFile once the file passes the limit.
 */
private fun readBounded(stream: InputStream, limit: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        total += read
        if (total > limit) {
            // The cap is worded from the same limit this loop holds; the fallback
            // stands in for a refusal that somehow comes back without any.
            throw RejectedFile(attachmentRefusal(total) ?: TextRef.of(R.string.staging_file_too_large))
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/**
 * The provider's display name and reported size for one picked file.
 *
 * @param context a context able to query the provider.
 * @param uri the picked file.
 * @return the name and the size, either of which the provider may not report.
 */
private fun pickerFile(context: Context, uri: Uri): Pair<String?, Long?> = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
        name to size
    }
}.getOrNull() ?: (null to null)
