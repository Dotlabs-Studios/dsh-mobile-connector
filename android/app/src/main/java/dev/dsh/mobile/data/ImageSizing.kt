package dev.dsh.mobile.data

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The long edge a photo is reduced to before it is sent to the model.
 *
 * A phone camera produces twelve megapixels; a model reads a picture, and the
 * difference between 12 MP and 1.5 MP is invisible to it while the upload is
 * roughly eight times the bytes over a mobile link. 1568 is the long edge the
 * major vision models resize to themselves, so anything past it is pixels sent to
 * be thrown away.
 */
const val MODEL_IMAGE_MAX_EDGE = 1568

/**
 * JPEG quality used when a photo is re-encoded.
 *
 * High enough that text in a photographed document survives, low enough that the
 * file is a fraction of the original.
 */
const val MODEL_IMAGE_QUALITY = 88

/**
 * Bytes below which a photo that is already small enough is sent untouched.
 *
 * Re-encoding a 900 KB JPEG costs a second of CPU on a phone and saves little; the
 * point of this pass is the twelve-megapixel photo, not the snapshot.
 */
const val MODEL_IMAGE_KEEP_BYTES = 1_200_000

/**
 * Whether a photo needs downscaling before it is sent.
 *
 * @param width decoded width in pixels.
 * @param height decoded height in pixels.
 * @param bytes encoded size in bytes.
 * @param mediaType the sniffed media type.
 * @return true when the photo exceeds the model's long edge or the byte budget.
 */
fun needsDownscale(width: Int, height: Int, bytes: Int, mediaType: String): Boolean {
    if (mediaType == "image/gif") return false
    return max(width, height) > MODEL_IMAGE_MAX_EDGE || bytes > MODEL_IMAGE_KEEP_BYTES
}

/**
 * Scale one dimension pair to fit a square of [maxEdge], keeping the aspect.
 *
 * @param width source width.
 * @param height source height.
 * @param maxEdge the longest edge allowed.
 * @return the target dimensions, never zero.
 */
fun scaledDimensions(width: Int, height: Int, maxEdge: Int = MODEL_IMAGE_MAX_EDGE): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= 0 || maxEdge <= 0 || longest <= maxEdge) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    val scale = maxEdge.toDouble() / longest
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/**
 * The power-of-two subsample that keeps an image's long edge near a target.
 *
 * `BitmapFactory` can only skip pixels in powers of two, so this is what turns a
 * 4000-pixel photo into something a 240-point card can hold without decoding the
 * whole thing into memory first. The result is never *below* [maxEdgePx] by more
 * than the rounding, because a bitmap that is too small then gets scaled up on
 * screen and looks worse than one sampled at the wrong step.
 *
 * @param width source width.
 * @param height source height.
 * @param maxEdgePx the longest edge the destination can use.
 * @return a sample size of 1, 2, 4, 8, …
 */
fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int): Int {
    if (maxEdgePx <= 0) return 1
    var sample = 1
    var longest = max(width, height)
    while (longest > 0 && longest / 2 >= maxEdgePx) {
        longest /= 2
        sample *= 2
    }
    return sample
}
