package dev.dsh.mobile.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.R
import dev.dsh.mobile.data.ImageSniffing
import dev.dsh.mobile.data.TextRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Base64

/**
 * Read a picked image, shrink it to what the model reads, and stage it.
 *
 * Everything expensive happens on an IO thread: reading the file, sniffing its
 * real media type, sampling it down to its long edge, re-encoding it, and only
 * then producing the canonical base64 the API carries. The base64 step used to run
 * on the main thread right after the picker returned, which is a freeze of several
 * seconds for a photo the model would have resized anyway.
 *
 * @param context a context able to open the content URI.
 * @param uri the picked image.
 */
fun AppViewModel.stagePhotoFrom(context: Context, uri: Uri) {
    viewModelScope.launch {
        val payload = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw RefusedPhoto(TextRef.of(R.string.staging_pick_unreadable))
                val mediaType = ImageSniffing.sniff(bytes)
                    ?: throw RefusedPhoto(TextRef.of(R.string.staging_photo_unsupported))
                // A name rather than a sentence: the provider's own is what the
                // store will be told, and the app's word for a photo is the
                // fallback when it reports none.
                val name = displayName(context, uri) ?: context.getString(R.string.label_photo)
                val prepared = prepareImageForUpload(bytes, mediaType) ?: PreparedImage(bytes, mediaType)
                if (prepared.bytes.size > MAX_UPLOAD_BYTES) {
                    throw RefusedPhoto(TextRef.of(R.string.label_image_too_large, prepared.bytes.size / 1024 / 1024))
                }
                // java.util.Base64 (API 26+) rather than android.util.Base64 keeps
                // this file — and therefore the whole staging path — exercisable
                // on the JVM.
                Triple(name, mediaType, Base64.getEncoder().encodeToString(prepared.bytes))
            }
        }
        payload.onSuccess { (name, mediaType, encoded) ->
            stagePhoto(name, mediaType, encoded)
        }.onFailure { error ->
            onPhotoRejected(
                when (error) {
                    is RefusedPhoto -> error.refusal
                    // The provider failing in its own words: nothing to translate,
                    // and more use than a sentence this app would have to invent.
                    else -> TextRef.of(error) ?: TextRef.of(R.string.staging_photo_unreadable)
                },
            )
        }
    }
}

/**
 * An image refused before it was read, carrying the reason to show.
 *
 * The reason is a [TextRef] rather than the exception's own message because it
 * is read by the user and not by a developer: the banner spells it, in the
 * language in force, and this exception carries no sentence of its own.
 *
 * @property refusal the sentence, decided but not spelled.
 */
private class RefusedPhoto(val refusal: TextRef) : IOException()

/**
 * Resolve the provider's display name for a picked file.
 *
 * @param context a context able to query the provider.
 * @param uri the picked file.
 * @return the display name when the provider reports one.
 */
private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

/** Mirrors the mobile API's per-image cap. */
private const val MAX_UPLOAD_BYTES = 12 * 1024 * 1024
