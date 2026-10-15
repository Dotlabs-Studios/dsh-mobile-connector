package dev.dsh.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's design tokens.
 *
 * The palette started as a port of the harness's `--dsw-*` CSS variables and keeps
 * their roles — canvas, layered surfaces, the one bubble, code surfaces, the label
 * ladder, state colours — so every composable still asks for a *role* rather than
 * a hex value. The values themselves are tuned for a phone chat app: a softly
 * tinted canvas so white cards read as cards, a brand blue taken from DeepSeek's
 * own, and a blue-to-violet gradient reserved for the primary action on a screen.
 *
 * Material's scheme cannot express all of it — there is no slot for a code
 * block, a diff tint, or a hairline — so the extra surfaces live here and are
 * read through [LocalHarness].
 */
data class HarnessPalette(
    /** The app canvas, and what an assistant message sits on. */
    val background: Color,
    /** A recessed fill: search fields, chips, the inside of a card. */
    val surfaceSunken: Color,
    /** Cards, sheets, the composer. */
    val surface: Color,
    /** The surface above a card. */
    val surfaceElevated: Color,
    /** The fill of a tonal control — a secondary button, a chip — on any surface. */
    val controlFill: Color,
    /** The operator's own message. */
    val userBubble: Color,
    /** A pressed or highlighted bubble. */
    val userBubbleHighlight: Color,
    /** The body of a fenced code block. */
    val codeBlock: Color,
    /** The banner above a code block. */
    val codeBlockBanner: Color,
    /** The fill behind inline code. */
    val inlineCode: Color,
    /** Headlines and body text. */
    val textPrimary: Color,
    /** Supporting text. */
    val textSecondary: Color,
    /** Metadata. */
    val textTertiary: Color,
    /** The dimmest readable label: placeholders, captions. */
    val textCaption: Color,
    /** The faintest divider. */
    val borderSubtle: Color,
    /** The hairline drawn on cards and controls. */
    val border: Color,
    /** The accent, for text, icons and selection. */
    val accent: Color,
    /** The label on an accent fill; white in both themes. */
    val onAccent: Color,
    /** A quiet accent tint: selected rows, icon badges, the working pill. */
    val accentContainer: Color,
    /** The first stop of the brand gradient. */
    val brandStart: Color,
    /** The last stop of the brand gradient. */
    val brandEnd: Color,
    /** Success: a finished chat, a granted approval, added lines. */
    val success: Color,
    /** Warning: a running chat, an approval waiting. */
    val warning: Color,
    /** The darker warning used for text, which must stay readable. */
    val warningText: Color,
    /** Failure. */
    val error: Color,
    /** The press/hover wash. */
    val hover: Color,
    /** The tint behind an added diff line. */
    val diffAdded: Color,
    /** The tint behind a removed diff line. */
    val diffRemoved: Color,
    /** Whether this is the dark palette, which a few composables branch on. */
    val isDark: Boolean,
) {
    /** The brand gradient, for the one primary action a screen has. */
    val brandGradient: Brush
        get() = Brush.linearGradient(listOf(brandStart, brandEnd))

    /** The same gradient, dimmed, for a primary action that cannot be used yet. */
    val brandGradientDisabled: Brush
        get() = Brush.linearGradient(listOf(brandStart.copy(alpha = 0.38f), brandEnd.copy(alpha = 0.38f)))
}

/** The light palette. */
val HarnessLight = HarnessPalette(
    background = Color(0xFFF5F6F8),
    surfaceSunken = Color(0xFFECEEF2),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    controlFill = Color(0xFFECEEF2),
    userBubble = Color(0xFFE6ECFF),
    userBubbleHighlight = Color(0xFFD3DDFF),
    codeBlock = Color(0xFFF4F5F8),
    codeBlockBanner = Color(0xFFEBEDF2),
    inlineCode = Color(0xFFEAECF1),
    textPrimary = Color(0xFF10131A),
    textSecondary = Color(0xFF4A5160),
    textTertiary = Color(0xFF6D7482),
    textCaption = Color(0xFF9AA0AC),
    // Alpha comes first in a Compose colour literal.
    borderSubtle = Color(0x0F10131A),
    border = Color(0x1A10131A),
    accent = Color(0xFF4D6BFE),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFE8EDFF),
    brandStart = Color(0xFF4D6BFE),
    brandEnd = Color(0xFF7B5CFA),
    success = Color(0xFF16A34A),
    warning = Color(0xFFF59E0B),
    warningText = Color(0xFFB45F06),
    error = Color(0xFFE5484D),
    hover = Color(0x0D10131A),
    diffAdded = Color(0x1A16A34A),
    diffRemoved = Color(0x1AE5484D),
    isDark = false,
)

/** The dark palette. */
val HarnessDark = HarnessPalette(
    background = Color(0xFF0E0F12),
    surfaceSunken = Color(0xFF16171C),
    surface = Color(0xFF1A1B20),
    surfaceElevated = Color(0xFF23252C),
    controlFill = Color(0xFF2B2D35),
    userBubble = Color(0xFF252B42),
    userBubbleHighlight = Color(0xFF323A5A),
    codeBlock = Color(0xFF131418),
    codeBlockBanner = Color(0xFF1C1D23),
    inlineCode = Color(0xFF262830),
    textPrimary = Color(0xFFF3F4F6),
    textSecondary = Color(0xFFBCC1CA),
    textTertiary = Color(0xFF8C929E),
    textCaption = Color(0xFF636975),
    borderSubtle = Color(0x0FFFFFFF),
    border = Color(0x1CFFFFFF),
    accent = Color(0xFF8197FF),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFF1F2540),
    brandStart = Color(0xFF4D6BFE),
    brandEnd = Color(0xFF7B5CFA),
    success = Color(0xFF34C268),
    warning = Color(0xFFF5A524),
    warningText = Color(0xFFF2B155),
    error = Color(0xFFFF6369),
    hover = Color(0x12FFFFFF),
    diffAdded = Color(0x2234C268),
    diffRemoved = Color(0x22FF6369),
    isDark = true,
)

/**
 * The app's corner radii.
 *
 * Soft, generous corners are most of what makes a chat app feel current: cards
 * at 18–22, controls at 12–14, the composer and sheets at 28, and pills fully
 * round.
 */
object HarnessRadius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 18.dp
    val xl = 22.dp
    val panel = 28.dp
    val pill = 999.dp
}

/**
 * The app's type scale.
 *
 * Body copy is 15sp: a phone is read at arm's length and in motion, and the
 * desktop's 14 felt cramped next to a thumb. Monospace is kept for what really
 * is code or a path; everything else that is small — timestamps, hints, metadata
 * — uses [caption], because a time set in a terminal face reads as a log line.
 */
object HarnessType {
    /** Message and markdown body. */
    val body = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)

    /** Supporting text: the second line of a row, a card's explanation. */
    val secondary = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)

    /** Small sans text: timestamps, hints, metadata. */
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

    /** Inline code. */
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

    /** Code inside a block, and paths. */
    val codeBlock = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp)

    /** The banner above a code block, and small section labels. */
    val codeBanner = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

    /** An uppercase section label above a group of rows. */
    val overline = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
}

/** The palette in scope for the current theme. */
val LocalHarness = staticCompositionLocalOf { HarnessLight }

/**
 * The app theme.
 *
 * Platform dynamic colour is deliberately not used: on a device with a wallpaper
 * palette it would repaint the app in colours unrelated to the brand, and a
 * consistent identity across phones is the point.
 *
 * @param darkTheme whether to use the dark palette.
 * @param content the themed content.
 */
@Composable
fun DshMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) HarnessDark else HarnessLight
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentContainer,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            secondaryContainer = palette.surfaceElevated,
            onSecondaryContainer = palette.textPrimary,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceSunken,
            onSurfaceVariant = palette.textSecondary,
            surfaceContainerLowest = palette.background,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surfaceElevated,
            surfaceContainerHighest = palette.surfaceElevated,
            error = palette.error,
            errorContainer = palette.error,
            onErrorContainer = palette.onAccent,
            outline = palette.border,
            outlineVariant = palette.borderSubtle,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentContainer,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            secondaryContainer = palette.surfaceSunken,
            onSecondaryContainer = palette.textPrimary,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceSunken,
            onSurfaceVariant = palette.textSecondary,
            surfaceContainerLowest = palette.surface,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface,
            surfaceContainerHighest = palette.surfaceSunken,
            error = palette.error,
            errorContainer = palette.error,
            onErrorContainer = palette.onAccent,
            outline = palette.border,
            outlineVariant = palette.borderSubtle,
        )
    }
    CompositionLocalProvider(LocalHarness provides palette) {
        MaterialTheme(colorScheme = scheme, typography = HarnessTypography, content = content)
    }
}

/**
 * Material's type scale, filled in with the app's sizes.
 *
 * Every role is set rather than left to the defaults, so a Material component
 * that picks its own role still lands on this scale.
 */
private val HarnessTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = HarnessType.body,
    bodySmall = HarnessType.secondary,
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)
