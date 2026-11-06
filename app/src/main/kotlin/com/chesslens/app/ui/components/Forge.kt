package com.chesslens.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chesslens.app.R
import com.chesslens.app.ui.theme.Lens
import kotlin.math.max

// ------------------------------------------------------------------------------------ surfaces

/** Top rim highlight of a bevelled slab: bright at the top edge, almost gone below. */
private val RimBrush = Brush.verticalGradient(0f to Lens.Rim, 0.3f to Color.Transparent, 1f to Color(0x12FFFFFF))

/** A slab lit from above: radial top light, rim highlight and a soft offset shadow. */
fun Modifier.forgeSlab(shape: Shape = RoundedCornerShape(28.dp), elevation: Dp = 16.dp): Modifier =
    this
        .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .drawBehind {
            drawRect(Lens.Slab)
            drawRect(
                Brush.radialGradient(
                    listOf(Lens.SlabLit, Lens.Slab),
                    center = Offset(size.width / 2, 0f),
                    radius = max(size.width, size.height) * 0.75f,
                ),
            )
        }
        .border(1.dp, RimBrush, shape)

/** A recessed well inside a slab (previews, boards, inputs). */
fun Modifier.forgeWell(shape: Shape = RoundedCornerShape(20.dp)): Modifier =
    this
        .clip(shape)
        .background(Lens.Well)
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), endY = 10.dp.toPx()))
        }
        .border(1.dp, Lens.WellLine, shape)

/** Fill shared by bevelled controls: light pooling from the bottom centre. */
private fun Modifier.bevelFill(top: Color, bottom: Color, glow: Color? = null): Modifier = drawBehind {
    drawRect(bottom)
    drawRect(Brush.radialGradient(listOf(top, bottom), center = Offset(size.width / 2, size.height * 1.02f), radius = max(size.width * 0.6f, size.height)))
    if (glow != null) {
        drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width / 2, size.height), radius = size.height * 1.1f))
    }
}

// ------------------------------------------------------------------------------------ controls

/** Bevelled neutral control (forge "button"): optional selected state with a green inner glow. */
@Composable
fun BevelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    description: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (top, bottom) = when {
        selected -> Color(0xFF0E1A13) to Color(0xFF050806)
        pressed -> Color(0xFF353535) to Color(0xFF171717)
        else -> Lens.ControlTop to Lens.ControlBottom
    }
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .bevelFill(top, bottom, glow = if (selected) Lens.GreenGlow else null)
            .border(1.dp, if (selected) Lens.Green.copy(alpha = 0.55f) else Lens.ControlBorder, shape)
            .border(1.dp, RimBrush, shape)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                if (description != null) contentDescription = description
            }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        content = content,
    )
}

/** Square bevelled icon button with a 48dp touch target (back, torch, corners…). */
@Composable
fun BevelIconButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = Lens.Text, selected: Boolean = false, onClick: () -> Unit) {
    BevelButton(onClick, modifier.size(48.dp), RoundedCornerShape(14.dp), selected, PaddingValues(0.dp), description) {
        Icon(icon, contentDescription = null, tint = if (selected) Lens.Green else tint, modifier = Modifier.size(22.dp))
    }
}

/** The one primary action per screen: green light pooled at the bottom, like a lit key. */
@Composable
fun GlowButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .heightIn(min = 52.dp)
            .then(if (enabled) Modifier.shadow(14.dp, shape, ambientColor = Lens.GreenGlow, spotColor = Lens.GreenGlow) else Modifier)
            .clip(shape)
            .drawBehind {
                drawRect(Color(0xFF00351A))
                if (!enabled) {
                    drawRect(Color(0xFF1A1F1C))
                    return@drawBehind
                }
                drawRect(
                    Brush.radialGradient(
                        listOf(if (pressed) Color(0xFF1BC96A) else Color(0xFF2CF08B), Color(0xFF00532A), Color(0xFF00351A)),
                        center = Offset(size.width / 2, size.height * 1.05f),
                        radius = max(size.width * 0.55f, size.height * 1.2f),
                    ),
                )
            }
            .border(1.dp, Color.Black, shape)
            .border(1.dp, Brush.verticalGradient(0f to Color(0x55FFFFFF), 0.4f to Color.Transparent, 1f to Color(0x33FFFFFF)), shape)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), role = Role.Button, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        val fg = if (enabled) Color.White else Lens.TextFaint
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), color = fg)
    }
}

/** Colour tile for list rows (forge settings): saturated light from the bottom, white glyph. */
@Composable
fun IconTile(icon: ImageVector, light: Color, deep: Color, size: Dp = 40.dp) {
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .bevelFill(light, deep)
            .border(1.dp, RimBrush, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

data class TabItem(val label: String, val icon: ImageVector? = null)

/** Header band of a composer slab with icon tabs; the selected tab glows green. */
@Composable
fun TabBand(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x662B2B2B), Color(0x66000000))))
            .drawBehind { drawLine(Lens.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { i, item ->
            val sel = i == selected
            val shape = CircleShape
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .then(
                        if (sel) Modifier
                            .bevelFill(Color(0xFF0E1A13), Color.Black, glow = Lens.GreenGlow)
                            .border(1.dp, Lens.Green.copy(alpha = 0.5f), shape)
                        else Modifier,
                    )
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = sel }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.icon?.let { Icon(it, null, tint = if (sel) Lens.Green else Lens.TextDim, modifier = Modifier.size(20.dp)) }
                Text(item.label, style = MaterialTheme.typography.labelLarge, color = if (sel) Lens.Text else Lens.TextDim)
            }
        }
        if (trailing != null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing() }
        }
    }
}

/** Suggestion / option chip: dark fill, hairline border; selected = green hairline + glow. */
@Composable
fun ForgeChip(label: String, selected: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(if (selected) Modifier.bevelFill(Color(0xFF0E1A13), Color(0xFF080B09), Lens.GreenGlow) else Modifier.background(Color(0xFF181818)))
            .border(1.dp, if (selected) Lens.Green.copy(alpha = 0.55f) else Color(0xFF2C2C2C), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) Lens.Green else Lens.TextDim, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Lens.Text)
    }
}

/** Centre pill of the top bar (forge search pill): status text with a soft top glow. */
@Composable
fun TopPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(Color(0xF0131313))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Color(0x40535353), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 0.5f))
            }
            .border(1.dp, Color(0xFF2E2E2E), CircleShape)
            .border(1.dp, RimBrush, CircleShape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** App mark inside a progress ring (forge avatar ring): the ring shows engine depth. */
@Composable
fun EngineRing(progress: Float, modifier: Modifier = Modifier, size: Dp = 48.dp, description: String = "Engine depth") {
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 3.dp.toPx()
            val inset = w / 2
            drawArc(Color(0xFF414141), 0f, 360f, false, topLeft = Offset(inset, inset), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w))
            drawArc(
                Lens.Green, -90f, 360f * progress.coerceIn(0f, 1f), false,
                topLeft = Offset(inset, inset), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        Box(Modifier.size(size - 10.dp).clip(CircleShape).background(Color(0xFF101513)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Forge meter: dark track, green fill with a glowing leading edge. */
@Composable
fun Meter(progress: Float, modifier: Modifier = Modifier, color: Color = Lens.Green) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color(0xFF191919))) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color))),
        )
    }
}

/** Section header row with an optional underlined text action ("Quick tools · View all"). */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Lens.Text, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.Underline),
                color = Lens.Text,
                modifier = Modifier.sizeIn(minHeight = 48.dp).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
    }
}

/** Segmented control in a recessed well; the selected segment is a lit, green-glowing key. */
@Composable
fun Segmented(options: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .forgeWell(RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = if (compact) 40.dp else 44.dp)
                    .clip(shape)
                    .then(
                        if (sel) Modifier.bevelFill(Color(0xFF13261A), Color(0xFF070A08), Lens.GreenGlow).border(1.dp, Lens.Green.copy(alpha = 0.5f), shape)
                        else Modifier,
                    )
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = sel }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                o.icon?.let { Icon(it, null, tint = if (sel) Lens.Green else Lens.TextMuted, modifier = Modifier.size(18.dp)) }
                Text(o.label, style = MaterialTheme.typography.labelMedium, color = if (sel) Lens.Text else Lens.TextDim, maxLines = 1)
            }
        }
    }
}
