package dev.dsh.mobile.ui

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.R
import dev.dsh.mobile.data.ApiException
import dev.dsh.mobile.data.TextRef
import dev.dsh.mobile.data.deliveryMimeType
import dev.dsh.mobile.data.deliverySaveName
import dev.dsh.mobile.data.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The three things a phone can do with a delivered file.
 *
 * A delivery is a file an agent produced *for the user*, and until now the phone
 * could only look at it: the harness writes the declaration to the session log and
 * the desktop opens the file natively, but nothing about that survives the trip to
 * Android. So the file needs a way out of the PC — into the Download folder, into
 * whichever app the phone has for that format, or into the share sheet — and each
 * of the three reads the bytes through the same authorized route.
 *
 * The bytes always come from the *host*, never from a link the phone could have
 * followed itself: the file lives next to the chat that produced it, its path was
 * resolved by the side that knows what a path means there, and the phone is not a
 * second entrance to that disk.
 *
 * Work happens on the IO dispatcher and reports back through the view model, so a
 * screen that scrolls away mid-download cannot leave a spinner that never stops.
 *
 * Every sentence this file shows the reader is a resource, and the ones the
 * phone's own filesystem decides are carried out of the failure as a [TextRef]:
 * what is left in an `IOException` is the technical message, which is what the
 * reader needs to see when nothing else can say what went wrong.
 */

/** Where a delivery is cached on its way to another app, under `cacheDir`. */
private const val DELIVERY_CACHE_DIR = "deliveries"

/**
 * What the *Scarica* button gets on a phone too old to write into Download
 * without a permission: a refusal, since the permission is deliberately never
 * asked for — the button that works there is *Condividi*.
 *
 * A refusal of the phone's own filesystem, so it travels as a reference and not
 * as a sentence: the store below Android 10 is not asked to write, and the text
 * that says so is drawn by the sheet that has a context.
 */
private val DOWNLOAD_NEEDS_Q = TextRef.of(R.string.delivery_download_needs_q)

/**
 * One refusal this file decided, carried as the resource that spells it.
 *
 * @property ref the sentence to show, with its values.
 */
private class DeliveryRefused(val ref: TextRef) : IOException()

/**
 * Save one delivered file into the phone's Download folder.
 *
 * The download happens inside the media store's own pending row rather than into
 * memory and then out again: the bytes are streamed once, straight to where they
 * will live, and a failure deletes the half-written entry instead of leaving a
 * broken file in a folder the user browses with another app.
 *
 * @param context a context able to reach the media store.
 * @param sessionId the chat the delivery belongs to.
 * @param seq the sequence of the delivery event.
 * @param index the file's position in that event.
 * @param declaredPath the path the agent declared, used for the file's name.
 */
fun AppViewModel.saveDelivery(
    context: Context,
    sessionId: String,
    seq: Int,
    index: Int,
    declaredPath: String,
) {
    if (deliveryBusy != null) return
    val name = deliverySaveName(declaredPath)
    val mime = deliveryMimeType(name)
    startDeliveryAction(sessionId, seq, index, "download")
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    publishToDownloads(context, sessionId, seq, index, name, mime)
                } else {
                    throw DeliveryRefused(DOWNLOAD_NEEDS_Q)
                }
            }
        }
        endDeliveryAction()
        outcome
            .onSuccess { noteDelivery(sessionId, seq, index, context.getString(R.string.delivery_saved_in, it), true) }
            .onFailure { noteDelivery(sessionId, seq, index, failureText(context, it), false) }
    }
}

/**
 * Open one delivered file with another app on the phone.
 *
 * The bytes are written to the app's cache first, because another app cannot be
 * handed a route into someone else's PC: it needs a file, and a `content:` URI the
 * FileProvider is willing to vouch for. That is also the honest answer to "is this
 * file available offline" — it is, from the moment the action finishes.
 *
 * @param context a context able to launch the intent.
 * @param sessionId the chat the delivery belongs to.
 * @param seq the sequence of the delivery event.
 * @param index the file's position in that event.
 * @param declaredPath the path the agent declared, used for the file's name.
 */
fun AppViewModel.openDeliveryExternally(
    context: Context,
    sessionId: String,
    seq: Int,
    index: Int,
    declaredPath: String,
) {
    if (deliveryBusy != null) return
    val name = deliverySaveName(declaredPath)
    val mime = deliveryMimeType(name)
    startDeliveryAction(sessionId, seq, index, "open")
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val cached = deliveryCacheFile(context, name)
                streamDelivery(sessionId, seq, index) { bytes -> cached.outputStream().use { bytes.copyTo(it) } }
                cached
            }
        }
        endDeliveryAction()
        outcome
            .onSuccess { cached ->
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(shareableUri(context, cached), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (start(context, intent)) {
                    noteDelivery(sessionId, seq, index, context.getString(R.string.delivery_open_done), true)
                } else {
                    noteDelivery(
                        sessionId,
                        seq,
                        index,
                        context.getString(R.string.delivery_open_no_app, name),
                        false,
                    )
                }
            }
            .onFailure { noteDelivery(sessionId, seq, index, failureText(context, it), false) }
    }
}

/**
 * Send one delivered file to another app through the share sheet.
 *
 * Deliberately the same cached file the open action writes: sharing is what a
 * phone does instead of a "save as" dialog, and it is the only way a delivery
 * reaches an app that does not register itself for the format.
 *
 * @param context a context able to launch the chooser.
 * @param sessionId the chat the delivery belongs to.
 * @param seq the sequence of the delivery event.
 * @param index the file's position in that event.
 * @param declaredPath the path the agent declared, used for the file's name.
 */
fun AppViewModel.shareDelivery(
    context: Context,
    sessionId: String,
    seq: Int,
    index: Int,
    declaredPath: String,
) {
    if (deliveryBusy != null) return
    val name = deliverySaveName(declaredPath)
    val mime = deliveryMimeType(name)
    startDeliveryAction(sessionId, seq, index, "share")
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val cached = deliveryCacheFile(context, name)
                streamDelivery(sessionId, seq, index) { bytes -> cached.outputStream().use { bytes.copyTo(it) } }
                cached
            }
        }
        endDeliveryAction()
        outcome
            .onSuccess { cached ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, shareableUri(context, cached))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(send, context.getString(R.string.delivery_share_label, name))
                if (!start(context, chooser)) {
                    noteDelivery(
                        sessionId,
                        seq,
                        index,
                        context.getString(R.string.delivery_share_no_app, name),
                        false,
                    )
                }
            }
            .onFailure { noteDelivery(sessionId, seq, index, failureText(context, it), false) }
    }
}

/**
 * Stream one delivery into a pending Download entry and publish it.
 *
 * @param context a context able to reach the media store.
 * @param sessionId the chat the delivery belongs to.
 * @param seq the sequence of the delivery event.
 * @param index the file's position in that event.
 * @param name the name the file is saved under.
 * @param mime the type the media store records, which is what a file manager
 *   uses to offer an app for it.
 * @returns the path shown to the user, relative to the phone's storage.
 * @throws DeliveryRefused when the media store refuses the entry or its stream,
 *   carrying the resource that says so.
 */
private suspend fun AppViewModel.publishToDownloads(
    context: Context,
    sessionId: String,
    seq: Int,
    index: Int,
    name: String,
    mime: String,
): String {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        // A row that is visible before its bytes are in place shows up in the
        // Files app as a file of unknown size; `IS_PENDING` is what keeps the
        // half-written one out of everyone else's view until it is whole.
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
        ?: throw DeliveryRefused(TextRef.of(R.string.delivery_store_refused_entry))
    try {
        streamDelivery(sessionId, seq, index) { bytes ->
            resolver.openOutputStream(uri)?.use { out -> bytes.copyTo(out) }
                ?: throw DeliveryRefused(TextRef.of(R.string.delivery_store_refused_stream))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (error: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
    // The name the media store actually kept, when it had to make room for it:
    // "Bilancio.xlsx" saved twice is "Bilancio (1).xlsx" on disk, and telling the
    // user the name they typed would send them looking for a file that is not
    // there — so the row is read back rather than assumed.
    val shown = runCatching { displayNameOf(context, uri) }.getOrNull()?.takeIf { it.isNotBlank() } ?: name
    return "${Environment.DIRECTORY_DOWNLOADS}/$shown"
}

/**
 * The name one media-store row ended up with.
 *
 * @param context a context able to query the provider.
 * @param uri the row.
 * @returns the recorded display name, or null when it cannot be read.
 */
private fun displayNameOf(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

/**
 * The cache file one delivery is written to before another app is handed it.
 *
 * Overwriting is intended: the PC's copy is the truth, and a cached file from an
 * earlier tap may be a partial one left by a download that failed.
 *
 * @param context a context whose cache directory is writable.
 * @param name the file's name.
 * @returns the file to write.
 */
internal fun deliveryCacheFile(context: Context, name: String): File {
    val dir = File(context.cacheDir, DELIVERY_CACHE_DIR)
    if (!dir.exists()) dir.mkdirs()
    return File(dir, name)
}

/**
 * The `content:` URI another app is allowed to read one cached delivery by.
 *
 * @param context a context whose package owns the provider.
 * @param file the cached file.
 * @returns the URI to hand over.
 */
private fun shareableUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/**
 * Launch one intent, reporting whether anything took it.
 *
 * `resolveActivity` is not the test: Android 11's package visibility can answer
 * "nothing" for an app that would have opened the file, and the only reliable
 * answer comes from the launch itself.
 *
 * @param context a context able to start an activity.
 * @param intent the intent to start.
 * @returns true when an activity accepted it.
 */
private fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * What one failed delivery action says beside the file.
 *
 * A refusal this file decided arrives as a [DeliveryRefused], and its sentence is
 * the resource it carries; anything else is either the host's own news or the
 * phone's filesystem talking, and both are shown as they came.
 *
 * @param context a context able to resolve the resource.
 * @param error the failure, from the host or from the phone's own filesystem.
 * @returns the sentence to show.
 */
private fun AppViewModel.failureText(context: Context, error: Throwable): String = when (error) {
    is DeliveryRefused -> error.ref.resolve(context)
    is ApiException -> deliveryMessage(error).resolve(context)
    else -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.delivery_failed_generic)
}
