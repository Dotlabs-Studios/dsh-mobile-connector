package dev.dsh.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R

/**
 * A text field: one rounded surface with a placeholder inside.
 *
 * Material's outlined field brings its own floating label and metrics; a chat
 * app's inputs are a soft filled shape with an optional glyph, which is this.
 *
 * @param value the current text.
 * @param onValueChange invoked on every edit.
 * @param placeholder the text shown while empty.
 * @param modifier layout for the field.
 * @param singleLine whether the field is one line.
 * @param keyboardType the keyboard to ask for.
 * @param imeAction the action key's behaviour.
 * @param maxLines how many lines the field grows to.
 * @param leadingIcon an optional glyph before the text.
 * @param filled true for the recessed fill used on a canvas (search), false for
 *   the white card fill used inside a form.
 * @param trailing optional content pinned to the right of the field.
 */
@Composable
fun HarnessTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    maxLines: Int = 1,
    leadingIcon: ImageVector? = null,
    filled: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val palette = LocalHarness.current
    HarnessSurface(
        modifier = modifier,
        background = if (filled) palette.surfaceSunken else palette.surface,
        border = if (filled) Color.Transparent else palette.border,
        radius = HarnessRadius.md,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = if (leadingIcon != null) 12.dp else 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = HarnessType.body,
                        color = palette.textCaption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    textStyle = TextStyle(
                        color = palette.textPrimary,
                        fontSize = HarnessType.body.fontSize,
                        lineHeight = HarnessType.body.lineHeight,
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.let {
                Spacer(Modifier.width(4.dp))
                it()
            }
        }
    }
}

/**
 * The primary action: a pill filled with the brand gradient.
 *
 * @param label the button's text.
 * @param onClick invoked when it is used.
 * @param modifier layout for the button.
 * @param enabled whether it can be used.
 * @param icon an optional leading icon.
 * @param large true for the taller variant used as a form's only action.
 */
@Composable
fun HarnessButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    large: Boolean = false,
) {
    val palette = LocalHarness.current
    Row(
        modifier
            .heightIn(min = if (large) 52.dp else 42.dp)
            .clip(RoundedCornerShape(HarnessRadius.pill))
            .background(if (enabled) palette.brandGradient else palette.brandGradientDisabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = if (large) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold) else HarnessType.secondary,
            fontWeight = FontWeight.SemiBold,
            color = palette.onAccent.copy(alpha = if (enabled) 1f else 0.8f),
            maxLines = 1,
        )
    }
}

/**
 * One list row: an optional leading glyph, a title and a detail line.
 *
 * @param title the row's primary line.
 * @param detail the secondary line.
 * @param onClick invoked when the row is tapped.
 * @param modifier layout for the row.
 * @param leading optional content before the title.
 * @param trailing optional content after the detail.
 * @param titleColor the primary line's colour.
 * @param monoDetail whether the detail is a path, which keeps the code face.
 */
@Composable
fun HarnessRow(
    title: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    titleColor: Color? = null,
    monoDetail: Boolean = false,
) {
    val palette = LocalHarness.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HarnessRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = HarnessType.body,
                fontWeight = FontWeight.Medium,
                color = titleColor ?: palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = if (monoDetail) HarnessType.codeBlock.copy(fontSize = HarnessType.caption.fontSize) else HarnessType.caption,
                    color = palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

/**
 * A round icon target.
 *
 * @param icon the glyph.
 * @param description the accessibility label.
 * @param modifier layout for the target.
 * @param tint the glyph's colour.
 * @param background the circle's fill, when it should have one.
 * @param size the circle's diameter.
 * @param iconSize the glyph's size.
 * @param bordered whether to draw a hairline round the circle.
 * @param enabled false for an action that has nothing to do here: the tap is
 *   swallowed and the glyph dims, which is how a control says "not now" instead
 *   of looking live and doing nothing.
 * @param onClick invoked when it is used.
 */
@Composable
fun HarnessIconTarget(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    background: Color? = null,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    bordered: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalHarness.current
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .then(if (bordered) Modifier.border(0.5.dp, palette.border, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (!enabled) palette.textCaption else (tint ?: palette.textSecondary),
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * A section label above a group of rows: small, uppercase, quiet.
 *
 * @param text the heading.
 * @param modifier layout for the heading.
 * @param trailing optional content on the right of the heading.
 */
@Composable
fun HarnessSectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    val palette = LocalHarness.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = HarnessType.overline,
            color = palette.textTertiary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/**
 * A hairline divider.
 *
 * @param modifier layout for the divider.
 * @param subtle whether to use the faintest border, as between rows in a card.
 */
@Composable
fun HarnessDivider(modifier: Modifier = Modifier, subtle: Boolean = false) {
    val palette = LocalHarness.current
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(if (subtle) palette.borderSubtle else palette.border),
    )
}

/**
 * The secondary action: a tonal pill.
 *
 * @param label the button's text.
 * @param modifier layout for the button.
 * @param enabled whether it can be used.
 * @param icon an optional leading glyph.
 * @param tint the label colour, for a destructive variant.
 * @param onClick invoked when it is used.
 */
@Composable
fun HarnessOutlineButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val palette = LocalHarness.current
    val color = when {
        !enabled -> palette.textCaption
        tint != null -> tint
        else -> palette.textPrimary
    }
    Row(
        modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(HarnessRadius.pill))
            .background(if (tint != null) tint.copy(alpha = if (palette.isDark) 0.16f else 0.10f) else palette.controlFill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = HarnessType.secondary,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * A compact pill: a glyph, a label, and an optional trailing glyph.
 *
 * Used for the controls that sit inside the composer and the top bar — the model,
 * the access preset — where a full button would shout.
 *
 * @param label the text.
 * @param onClick invoked when it is used.
 * @param modifier layout for the chip.
 * @param leadingIcon an optional glyph before the label.
 * @param trailingIcon an optional glyph after the label, usually a disclosure.
 * @param tint the label and glyph colour.
 * @param background the pill's fill.
 * @param enabled whether it can be used.
 */
@Composable
fun HarnessChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    tint: Color? = null,
    background: Color? = null,
    enabled: Boolean = true,
) {
    val palette = LocalHarness.current
    val color = tint ?: palette.textSecondary
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(HarnessRadius.pill))
            .background(background ?: palette.controlFill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = if (leadingIcon != null) 10.dp else 12.dp, end = if (trailingIcon != null) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = HarnessType.caption,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(2.dp))
            Icon(trailingIcon, contentDescription = null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * A segmented control: two or three choices in one rounded track.
 *
 * @param options the labels, in order.
 * @param selected the index of the current choice.
 * @param onSelect invoked with the index tapped.
 * @param modifier layout for the track.
 * @param compact true for the shorter track used above the composer.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val palette = LocalHarness.current
    Row(
        modifier
            .clip(RoundedCornerShape(HarnessRadius.pill))
            .background(palette.surfaceSunken)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            val fill by animateColorAsState(
                if (active) (if (palette.isDark) palette.surfaceElevated else palette.surface) else Color.Transparent,
                label = "segment-fill",
            )
            Box(
                Modifier
                    .weight(1f)
                    .then(
                        if (active && !palette.isDark) {
                            Modifier.shadow(1.dp, RoundedCornerShape(HarnessRadius.pill))
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(HarnessRadius.pill))
                    .background(fill)
                    .clickable { onSelect(index) }
                    .padding(vertical = if (compact) 6.dp else 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = if (compact) HarnessType.caption else HarnessType.secondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) palette.textPrimary else palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The heading every bottom sheet opens with.
 *
 * @param title the sheet's name.
 * @param subtitle one line saying what it is for.
 * @param modifier layout for the header.
 * @param leading an optional badge before the title.
 * @param actions trailing controls.
 */
@Composable
fun SheetHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalHarness.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
            subtitle?.let {
                Text(text = it, style = HarnessType.secondary, color = palette.textTertiary)
            }
        }
        actions()
    }
}

/**
 * A glyph in a small tinted rounded square.
 *
 * @param icon the glyph.
 * @param tint the glyph's colour.
 * @param background the square's fill.
 * @param size the square's edge.
 * @param iconSize the glyph's size.
 * @param modifier layout for the badge.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * The app's mark: the harness's whale, white on the brand gradient.
 *
 * The launcher foreground is reused rather than a second copy of the artwork:
 * it is already the whale's silhouette, centred with the adaptive-icon margin,
 * so drawing it slightly larger than its box crops that margin away.
 *
 * @param size the mark's edge.
 * @param modifier layout for the mark.
 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val palette = LocalHarness.current
    Box(
        modifier
            .size(size)
            .then(
                if (!palette.isDark) {
                    Modifier.shadow(
                        elevation = size * 0.18f,
                        shape = RoundedCornerShape(size * 0.32f),
                        ambientColor = palette.brandStart,
                        spotColor = palette.brandStart,
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(size * 0.32f))
            .background(palette.brandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.requiredSize(size * 1.25f),
        )
    }
}
