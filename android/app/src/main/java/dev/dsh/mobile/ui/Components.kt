package dev.dsh.mobile.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dsh.mobile.R
import dev.dsh.mobile.data.sampleSizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * A rounded surface with a hairline edge and, in the light theme, a soft shadow.
 *
 * The border is half a point wide so a card gains an edge without gaining
 * layout. The shadow is skipped in the dark theme, where it would only muddy the
 * canvas: there the lighter surface and the hairline already lift the card.
 *
 * @param modifier layout for the card.
 * @param background the fill.
 * @param border the edge colour.
 * @param radius the corner radius.
 * @param elevation the shadow's depth in the light theme; zero for a flat card.
 * @param content the card's contents.
 */
@Composable
fun HarnessSurface(
    modifier: Modifier = Modifier,
    background: Color = LocalHarness.current.codeBlock,
    border: Color = LocalHarness.current.borderSubtle,
    radius: Dp = HarnessRadius.lg,
    elevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val palette = LocalHarness.current
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .then(
                if (elevation > 0.dp && !palette.isDark) {
                    Modifier.shadow(
                        elevation = elevation,
                        shape = shape,
                        ambientColor = Color(0xFF1B2440).copy(alpha = 0.10f),
                        spotColor = Color(0xFF1B2440).copy(alpha = 0.16f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(background)
            .border(0.5.dp, border, shape),
    ) {
        content()
    }
}

/**
 * Draw text with the harness's shimmering sweep.
 *
 * The desktop animates a gradient across the glyphs, which is what makes a
 * running row read as alive rather than as merely coloured. The sweep is a text
 * *brush* rather than a rectangle drawn over the text: a brush is masked to the
 * glyphs by construction, so the highlight can never spill into a band across
 * the empty space between letters.
 *
 * @param text the text to draw.
 * @param style its style.
 * @param base the resting colour.
 * @param highlight the colour the sweep carries.
 * @param modifier layout for the text.
 */
@Composable
fun ShimmerText(
    text: String,
    style: TextStyle,
    base: Color,
    highlight: Color,
    modifier: Modifier = Modifier,
) {
    // The band has to travel the text's own width, which is only knowable after
    // a layout pass; the first pass draws the resting colour.
    var measured by remember { mutableStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-progress",
    )
    val width = measured
    val band = if (width <= 0f) 1f else width * 0.7f
    val start = -band + progress * (width + band)
    val shaded = width > 0f
    Text(
        text = text,
        style = if (shaded) {
            style.copy(
                brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(start, 0f),
                    end = Offset(start + band, 0f),
                ),
            )
        } else {
            style
        },
        color = if (shaded) Color.Unspecified else base,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout -> measured = layout.size.width.toFloat() },
        modifier = modifier,
    )
}

/**
 * A small ring that reads as "this is running", matching the harness's state dot.
 *
 * @param color the ring colour.
 * @param size the ring's outer size.
 * @param modifier layout for the ring.
 */
@Composable
fun RunningRing(color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
        label = "ring-angle",
    )
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.16f
        drawArc(
            color = color.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = stroke),
        )
    }
}

/**
 * Bytes of encoded images kept in memory.
 *
 * A transcript can show a dozen photos, and re-downloading one because a scroll
 * moved it off screen is exactly the cost this cache exists to remove. 24 MiB is
 * roughly two dozen phone photos after the downscale.
 */
private const val IMAGE_CACHE_BYTES = 24 * 1024 * 1024

/**
 * Longest edge the full-screen preview decodes.
 *
 * A phone screen is about 1200 pixels wide; 2048 keeps a photo sharp when it is
 * zoomed by the platform's own viewer without decoding twelve megapixels into
 * 48 MB for a dialog.
 */
private const val LIGHTBOX_IMAGE_MAX_PX = 2048

/**
 * A token-aware HTTP image loader for transcript images.
 *
 * The harness serves image bytes behind the same bearer token as the rest of the
 * API, so a generic image library would need a custom interceptor anyway;
 * decoding inline keeps the app dependency-light.
 */
object ImageBytesCache {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, ByteArray>(IMAGE_CACHE_BYTES) {
        // Bytes, not kilobytes: sized in kilobytes the ceiling was 24 KB, so every
        // photo was evicted the moment it was put and every scroll re-downloaded it.
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /**
     * Fetch one image's bytes, memoising per URL.
     *
     * @param url the raw image route.
     * @param token the bearer token authorizing it.
     * @return the encoded image bytes, or null on failure.
     */
    suspend fun load(url: String, token: String?): ByteArray? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).apply {
                    token?.let { header("authorization", "Bearer $it") }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.bytes()
                }
            }.getOrNull()?.also { cache.put(url, it) }
        }
    }
}

/**
 * What a transcript image is doing right now.
 *
 * The distinction between "loading" and "failed" is the whole point: an image
 * that never arrives used to show a spinner forever, which is indistinguishable
 * from an image that is simply slow — and leaves the reader with no way to tell
 * a network problem from a bug, and no way to act.
 */
sealed interface ImageLoad {
    /** The bytes are on their way. */
    data object Loading : ImageLoad

    /** The image decoded and can be drawn. */
    data class Ready(val bitmap: ImageBitmap) : ImageLoad

    /** The request or the decode failed; the reader can retry. */
    data object Failed : ImageLoad
}

/**
 * Load and decode one transcript image, re-reading it when [attempt] changes.
 *
 * @param url the raw image route.
 * @param token the bearer token authorizing the request.
 * @param maxEdgePx the longest edge the destination can actually use, so a 12 MP
 *   photo is not decoded into 48 MB to be drawn in a 64-point tile.
 * @param attempt a counter the caller bumps to retry a failed load.
 * @return the load's current state.
 */
@Composable
fun rememberImageLoad(url: String, token: String?, maxEdgePx: Int = 1024, attempt: Int = 0): ImageLoad {
    val state by produceState<ImageLoad>(initialValue = ImageLoad.Loading, url, token, attempt, maxEdgePx) {
        val bytes = ImageBytesCache.load(url, token)
        value = if (bytes == null) {
            ImageLoad.Failed
        } else {
            val decoded = withContext(Dispatchers.Default) { decodeBounded(bytes, maxEdgePx) }
            if (decoded == null) ImageLoad.Failed else ImageLoad.Ready(decoded)
        }
    }
    return state
}

/**
 * Decode one encoded image, skipping pixels until its long edge fits.
 *
 * @param bytes the encoded image.
 * @param maxEdgePx the longest edge the destination can use.
 * @return the decoded bitmap, or null when the bytes are not an image.
 */
private fun decodeBounded(bytes: ByteArray, maxEdgePx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdgePx)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/**
 * One design edge converted to the pixels this screen will really use.
 *
 * The extra quarter covers the density rounding and a light upscale by
 * `ContentScale`, which costs a few pixels and avoids a visibly soft image.
 *
 * @param edge the edge in dp.
 * @return the target pixel size for decoding.
 */
@Composable
private fun edgePx(edge: Dp): Int = with(LocalDensity.current) { (edge.toPx() * 1.25f).roundToInt() }

/** One image reference as the transcript holds it. */
data class ImageRef(
    /** The authorized raw route. */
    val url: String,
    /** The recorded width, when the host reported one. */
    val width: Int? = null,
    /** The recorded height, when the host reported one. */
    val height: Int? = null,
    /** The file name, shown as a caption and used as the accessibility label. */
    val name: String? = null,
)

/** The long edge of a lone transcript image, matching the harness. */
private val SINGLE_IMAGE_EDGE = 240.dp

/** The square a gallery tile occupies, matching the harness. */
private val TILE_IMAGE_EDGE = 64.dp

/** The narrowest and widest aspect a lone image is allowed to take. */
private const val MIN_ASPECT = 0.25f
private const val MAX_ASPECT = 4f

/**
 * Render the images of one message or tool result.
 *
 * The harness shows a lone image at up to 240 points on its long edge and folds
 * the rest of a set into 64-point tiles. Both are reproduced here, because that
 * rule is what keeps one screenshot from consuming the whole transcript.
 *
 * @param images the images to draw, in order.
 * @param token the bearer token authorizing the requests.
 * @param modifier layout for the gallery.
 * @param alignEnd whether the gallery hugs the right edge, as a user message does.
 * @param tileOnly whether to use the compact tile strip even for a single image,
 *   which a collapsed tool row needs: a 240-point image there would dwarf the
 *   row it belongs to.
 * @param onOpen invoked with the index of the image the user tapped.
 */
@Composable
fun AttachmentGallery(
    images: List<ImageRef>,
    token: String?,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    tileOnly: Boolean = false,
    onOpen: (Int) -> Unit = {},
) {
    if (images.isEmpty()) return
    if (images.size == 1 && !tileOnly) {
        // The single image routes its own taps, because a tap means "open" once
        // it has loaded and "retry" until then.
        SingleImage(images.single(), token, modifier, onReady = { onOpen(0) })
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        // The desktop wraps its gallery; a phone shows a bounded horizontal
        // strip instead, which is the same set of images in one row.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(images.size) { index ->
                TileImage(images[index], token, onClick = { onOpen(index) })
            }
        }
    }
}

/**
 * One square tile of a multi-image gallery.
 *
 * A tap means two different things depending on state — open the preview, or
 * retry a load that failed — and which one it is cannot be known from the tile
 * alone, so the tap is routed by the load's own state rather than by a guess.
 *
 * @param image the image to draw.
 * @param token the bearer token authorizing the request.
 * @param onClick invoked when a loaded tile is tapped.
 */
@Composable
private fun TileImage(image: ImageRef, token: String?, onClick: () -> Unit) {
    val palette = LocalHarness.current
    var attempt by remember(image.url) { mutableStateOf(0) }
    val load = rememberImageLoad(image.url, token, edgePx(TILE_IMAGE_EDGE), attempt)
    Box(
        Modifier
            .size(TILE_IMAGE_EDGE)
            .clip(RoundedCornerShape(HarnessRadius.lg))
            .background(palette.surfaceSunken)
            .border(0.5.dp, palette.borderSubtle, RoundedCornerShape(HarnessRadius.lg)),
        contentAlignment = Alignment.Center,
    ) {
        ImageContent(
            load = load,
            image = image,
            scale = ContentScale.Crop,
            compact = true,
            onClick = onClick,
            onRetry = { attempt += 1 },
        )
    }
}

/**
 * Draw one image at the harness's lone-image geometry.
 *
 * The frame is sized from the dimensions the host recorded whenever it reported
 * them, so the transcript does not reflow when the bitmap finally lands — and so
 * a failed image still occupies the space it was going to.
 *
 * @param image the image to draw.
 * @param token the bearer token authorizing the request.
 * @param modifier layout, which carries the tap target.
 * @param onReady invoked when a loaded image is tapped.
 */
@Composable
private fun SingleImage(
    image: ImageRef,
    token: String?,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
) {
    val palette = LocalHarness.current
    var attempt by remember(image.url) { mutableStateOf(0) }
    val load = rememberImageLoad(image.url, token, edgePx(SINGLE_IMAGE_EDGE), attempt)
    val aspect = aspectOf(image, load)
    val box = if (aspect >= 1f) {
        Modifier.width(SINGLE_IMAGE_EDGE).height(SINGLE_IMAGE_EDGE / aspect)
    } else {
        Modifier.height(SINGLE_IMAGE_EDGE).width(SINGLE_IMAGE_EDGE * aspect)
    }
    Box(
        modifier
            .then(box)
            .clip(RoundedCornerShape(HarnessRadius.lg))
            .background(palette.surfaceSunken)
            .border(0.5.dp, palette.borderSubtle, RoundedCornerShape(HarnessRadius.lg)),
        contentAlignment = Alignment.Center,
    ) {
        ImageContent(
            load = load,
            image = image,
            scale = ContentScale.Fit,
            compact = false,
            onClick = onReady,
            onRetry = { attempt += 1 },
        )
    }
}

/**
 * The aspect ratio of one image, preferring what the host recorded.
 *
 * @param image the image reference.
 * @param load its current load state, whose bitmap is the fallback.
 * @return the width divided by the height.
 */
private fun aspectOf(image: ImageRef, load: ImageLoad): Float {
    val width = image.width
    val height = image.height
    if (width != null && height != null && height > 0) {
        return (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)
    }
    val bitmap = (load as? ImageLoad.Ready)?.bitmap
    if (bitmap != null && bitmap.height > 0) {
        return (bitmap.width.toFloat() / bitmap.height).coerceIn(MIN_ASPECT, MAX_ASPECT)
    }
    return 1f
}

/**
 * Draw whatever an image's load state calls for, filling its caller's frame.
 *
 * @param load the load state.
 * @param image the image reference, for the accessibility label.
 * @param scale how a decoded bitmap fills its frame.
 * @param compact whether the frame is small enough that only a glyph fits.
 * @param onClick invoked when a loaded image is tapped.
 * @param onRetry invoked when a failed image is tapped.
 */
@Composable
private fun ImageContent(
    load: ImageLoad,
    image: ImageRef,
    scale: ContentScale,
    compact: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val palette = LocalHarness.current
    when (load) {
        is ImageLoad.Loading -> RunningRing(palette.textTertiary, if (compact) 12.dp else 16.dp)

        is ImageLoad.Ready -> Image(
            bitmap = load.bitmap,
            contentDescription = image.name ?: stringResource(R.string.component_image),
            contentScale = scale,
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
        )

        ImageLoad.Failed -> FailedImage(retry = onRetry, compact = compact)
    }
}

/**
 * What a reader sees when an image did not arrive.
 *
 * The harness shows a retry control with a reason instead of an empty frame, and
 * the reason is the part that matters: without it, a broken image and a slow one
 * look the same, and there is nothing to try.
 *
 * @param retry invoked when the reader asks to try again.
 * @param compact whether the frame is small enough that only a glyph fits.
 */
@Composable
private fun FailedImage(retry: () -> Unit, compact: Boolean) {
    val palette = LocalHarness.current
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = retry),
        contentAlignment = Alignment.Center,
    ) {
        if (compact) {
            Icon(
                Icons.Filled.BrokenImage,
                contentDescription = stringResource(R.string.component_image_failed_retry),
                tint = palette.error,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(8.dp),
            ) {
                Icon(
                    Icons.Filled.BrokenImage,
                    contentDescription = null,
                    tint = palette.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.component_image_failed), style = HarnessType.caption, color = palette.textSecondary)
                Text(stringResource(R.string.component_image_retry), style = HarnessType.caption, color = palette.textCaption)
            }
        }
    }
}

/**
 * Show one image filling the screen, the way the harness's lightbox does.
 *
 * The harness's preview is fit-to-viewport with no zoom and no download, closed
 * by Escape, the mask, or the close control. On a phone the same three exits are
 * the back gesture, a tap on the mask, and the close button.
 *
 * @param image the image to show.
 * @param token the bearer token authorizing the request.
 * @param onDismiss invoked when the user closes the preview.
 */
@Composable
fun ImageLightbox(image: ImageRef, token: String?, onDismiss: () -> Unit) {
    val palette = LocalHarness.current
    var attempt by remember(image.url) { mutableStateOf(0) }
    val load = rememberImageLoad(image.url, token, LIGHTBOX_IMAGE_MAX_PX, attempt)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (load) {
                    is ImageLoad.Loading -> RunningRing(palette.textTertiary, 28.dp)

                    is ImageLoad.Ready -> Image(
                        bitmap = load.bitmap,
                        contentDescription = image.name ?: stringResource(R.string.component_image),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // The preview is the last place a failure should be silent:
                    // an empty black screen looks like a broken viewer rather
                    // than like a request that can simply be tried again.
                    ImageLoad.Failed -> Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { attempt += 1 },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.BrokenImage,
                                contentDescription = null,
                                tint = palette.error,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.component_image_failed), style = HarnessType.body, color = Color.White)
                            Text(
                                stringResource(R.string.component_image_retry),
                                style = HarnessType.caption,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .statusBarsPadding()
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.component_close_preview),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            image.name?.let { name ->
                Text(
                    text = name,
                    style = HarnessType.secondary,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                )
            }
        }
    }
}

/**
 * The control that returns a transcript to its newest message.
 *
 * It carries a shadow rather than only a hairline because it floats over text:
 * without one it reads as a glyph lying on the message it is covering, which is
 * the opposite of what a control is.
 *
 * @param onClick invoked when it is used.
 * @param modifier layout, which carries the corner it is pinned to.
 */
@Composable
fun ScrollToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalHarness.current
    Box(
        modifier
            .size(40.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(if (palette.isDark) palette.surfaceElevated else palette.surface)
            .border(0.5.dp, palette.border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(R.string.component_scroll_to_bottom),
            tint = palette.textPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** One labelled body inside an IN/OUT card. */
data class IoSections(
    /** The gutter label. */
    val label: String,
    /** The body text. */
    val text: String,
    /** Whether to paint the body in the error colour. */
    val isError: Boolean = false,
)

/**
 * The harness's IN/OUT card: labelled monospace bodies in one bordered box.
 *
 * @param sections the bodies to draw, in order.
 * @param modifier layout for the card.
 */
@Composable
fun IoCard(sections: List<IoSections>, modifier: Modifier = Modifier) {
    if (sections.isEmpty()) return
    val palette = LocalHarness.current
    HarnessSurface(modifier = modifier.fillMaxWidth(), background = palette.codeBlock, radius = HarnessRadius.md) {
        Column {
            sections.forEachIndexed { index, section ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(palette.border),
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(HarnessRadius.xs))
                            .background(if (section.isError) palette.error.copy(alpha = 0.12f) else palette.hover)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = section.label,
                            style = HarnessType.overline.copy(fontSize = 10.sp),
                            color = if (section.isError) palette.error else palette.textTertiary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = section.text,
                        style = HarnessType.codeBlock,
                        color = if (section.isError) palette.error else palette.textSecondary,
                        softWrap = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/**
 * A code block with the harness's banner and monospace body.
 *
 * @param language the fence's language, shown in the banner when there is no title.
 * @param title an explicit title, which wins over the language.
 * @param code the block's body.
 * @param modifier layout for the block.
 */
@Composable
fun CodeBlock(
    language: String?,
    title: String?,
    code: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHarness.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    HarnessSurface(
        modifier = modifier.fillMaxWidth(),
        background = palette.codeBlock,
        border = palette.border,
        radius = HarnessRadius.md,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(palette.codeBlockBanner)
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Code,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title ?: language ?: stringResource(R.string.component_code),
                    style = HarnessType.caption,
                    fontWeight = FontWeight.Medium,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(HarnessRadius.pill))
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = null,
                        tint = if (copied) palette.success else palette.textTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(if (copied) R.string.component_copied else R.string.component_copy),
                        style = HarnessType.caption,
                        color = if (copied) palette.success else palette.textTertiary,
                    )
                }
            }
            Text(
                text = code,
                style = HarnessType.codeBlock,
                color = palette.textPrimary,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}
