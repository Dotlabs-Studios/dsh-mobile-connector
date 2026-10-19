package dev.dsh.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.dsh.mobile.data.MODEL_IMAGE_MAX_EDGE
import dev.dsh.mobile.data.MODEL_IMAGE_QUALITY
import dev.dsh.mobile.data.needsDownscale
import dev.dsh.mobile.data.sampleSizeFor
import dev.dsh.mobile.data.scaledDimensions
import java.io.ByteArrayOutputStream

/** One photo, ready to upload: the bytes and the media type that describes them. */
data class PreparedImage(val bytes: ByteArray, val mediaType: String)

/**
 * Reduce a picked photo to the pixels a model actually reads.
 *
 * A phone camera produces about twelve megapixels; the model resizes anything past
 * roughly 1.5 MP on its own, so the extra bytes are uploaded, paid for on a mobile
 * link and thrown away. This samples on decode (a twelve-megapixel bitmap is 48 MB
 * in memory, which is how a photo picker used to OOM), scales to the long edge and
 * re-encodes at a quality that keeps photographed text readable.
 *
 * GIF is untouched — re-encoding it would lose the animation — and a photo that is
 * already inside both bounds keeps its original bytes and quality.
 *
 * @param bytes the picked file's bytes.
 * @param mediaType the sniffed media type.
 * @return the smaller photo, or null when the original should be sent as it is.
 */
fun prepareImageForUpload(bytes: ByteArray, mediaType: String): PreparedImage? {
    if (mediaType != "image/jpeg" && mediaType != "image/png") return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    if (!needsDownscale(bounds.outWidth, bounds.outHeight, bytes.size, mediaType)) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MODEL_IMAGE_MAX_EDGE)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val (targetWidth, targetHeight) = scaledDimensions(sampled.width, sampled.height)
    val scaled = if (sampled.width == targetWidth && sampled.height == targetHeight) {
        sampled
    } else {
        Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true)
    }
    val format = if (mediaType == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    val out = ByteArrayOutputStream(sampled.byteCount / 4)
    val written = scaled.compress(format, MODEL_IMAGE_QUALITY, out)
    if (scaled !== sampled) scaled.recycle()
    sampled.recycle()
    if (!written) return null
    val encoded = out.toByteArray()
    // Never hand back something bigger than what came in: a "smaller" photo that
    // uploads more bytes is a worse photo, not a better one.
    if (encoded.size >= bytes.size) return null
    return PreparedImage(encoded, mediaType)
}
