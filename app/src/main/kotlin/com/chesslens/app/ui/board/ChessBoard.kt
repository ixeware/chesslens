package com.chesslens.app.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Move
import com.chesslens.chess.MoveQuality
import com.chesslens.chess.Square
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class BoardArrow(val from: Int, val to: Int, val color: Color, val width: Float = 0.18f, val label: String? = null)

/**
 * 2D board used for the mini-map and the analysis screen. Supports arrows, highlights,
 * check glow, a move-quality badge, a control heat map and tap-to-move.
 */
@Composable
fun ChessBoard(
    squares: IntArray,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    lastMove: Int = Move.NONE,
    arrows: List<BoardArrow> = emptyList(),
    checkSquare: Int = -1,
    badge: Pair<Int, MoveQuality>? = null,
    legalMoves: List<Int> = emptyList(),
    heat: IntArray? = null,
    hanging: List<Int> = emptyList(),
    showCoordinates: Boolean = true,
    onMove: ((from: Int, to: Int) -> Unit)? = null,
) {
    var selected by remember { mutableIntStateOf(-1) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val badgeAnim = remember { Animatable(0f) }
    LaunchedEffect(badge) {
        badgeAnim.snapTo(0f)
        if (badge != null) badgeAnim.animateTo(1f, tween(450))
    }
    val targets = if (selected >= 0) legalMoves.filter { Move.from(it) == selected }.map { Move.to(it) }.toSet() else emptySet()

    val tapModifier = if (onMove != null) Modifier.pointerInput(flipped, legalMoves) {
        detectTapGestures { pos ->
            val cell = size.width / 8f
            val col = (pos.x / cell).toInt().coerceIn(0, 7)
            val row = (pos.y / cell).toInt().coerceIn(0, 7)
            val sq = squareAt(col, row, flipped)
            if (selected >= 0 && legalMoves.any { Move.from(it) == selected && Move.to(it) == sq }) {
                onMove(selected, sq)
                selected = -1
            } else if (legalMoves.any { Move.from(it) == sq }) {
                selected = if (selected == sq) -1 else sq
            } else {
                selected = -1
            }
        }
    } else Modifier

    Canvas(modifier.aspectRatio(1f).then(tapModifier)) {
        val cell = size.width / 8f
        for (sq in 0 until 64) {
            val tl = topLeft(sq, cell, flipped)
            val light = Square.isLight(sq)
            drawRect(if (light) Lens.BoardLight else Lens.BoardDark, tl, Size(cell, cell))
            heat?.let { h ->
                val v = h[sq]
                if (v != 0) drawRect((if (v > 0) Lens.Neon else Lens.Red).copy(alpha = (minOf(kotlin.math.abs(v), 3) / 3f) * 0.35f), tl, Size(cell, cell))
            }
        }
        if (lastMove != Move.NONE) {
            for (sq in listOf(Move.from(lastMove), Move.to(lastMove))) drawRect(Lens.Yellow.copy(alpha = 0.42f), topLeft(sq, cell, flipped), Size(cell, cell))
        }
        if (selected >= 0) drawRect(Lens.Neon.copy(alpha = 0.45f), topLeft(selected, cell, flipped), Size(cell, cell))
        if (checkSquare >= 0) {
            val c = topLeft(checkSquare, cell, flipped) + Offset(cell / 2, cell / 2)
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(listOf(Lens.Red, Lens.Red.copy(alpha = 0f)), c, cell * 0.7f),
                cell * 0.7f, c,
            )
        }
        for (sq in hanging) {
            val c = topLeft(sq, cell, flipped) + Offset(cell / 2, cell / 2)
            drawCircle(Lens.Red.copy(alpha = 0.8f), cell * 0.46f, c, style = Stroke(cell * 0.06f))
        }
        if (showCoordinates) drawCoordinates(this, cell, flipped, measurer, density.density)
        for (sq in 0 until 64) {
            val p = squares[sq]
            if (p != 0) PieceRenderer.draw(this, p, topLeft(sq, cell, flipped), cell)
        }
        for (t in targets) {
            val c = topLeft(t, cell, flipped) + Offset(cell / 2, cell / 2)
            if (squares[t] != 0) drawCircle(Color.Black.copy(alpha = 0.3f), cell * 0.46f, c, style = Stroke(cell * 0.08f))
            else drawCircle(Color.Black.copy(alpha = 0.25f), cell * 0.16f, c)
        }
        for (a in arrows.reversed()) drawArrow(this, a, cell, flipped)
        badge?.let { (sq, q) ->
            val k = badgeAnim.value
            val tl = topLeft(sq, cell, flipped)
            val c = Offset(tl.x + cell * 0.88f, tl.y + cell * 0.12f)
            val r = cell * 0.24f * (if (k < 0.7f) k / 0.7f * 1.15f else 1.15f - (k - 0.7f) / 0.3f * 0.15f)
            drawCircle(Color(q.argb), r, c)
            drawCircle(Color.White, r, c, style = Stroke(cell * 0.03f))
            val label = when (q) {
                MoveQuality.BEST -> "★"
                MoveQuality.EXCELLENT -> "!"
                MoveQuality.GOOD -> "✓"
                MoveQuality.BOOK -> "≡"
                else -> q.symbol
            }
            val layout = measurer.measure(label, TextStyle(color = Color.White, fontSize = (r * 1.1f / density.density).sp, fontWeight = FontWeight.Bold))
            drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
        }
    }
}

private fun squareAt(col: Int, row: Int, flipped: Boolean): Int =
    if (flipped) (row) * 8 + (7 - col) else (7 - row) * 8 + col

private fun topLeft(sq: Int, cell: Float, flipped: Boolean): Offset {
    val f = sq and 7
    val r = sq shr 3
    val col = if (flipped) 7 - f else f
    val row = if (flipped) r else 7 - r
    return Offset(col * cell, row * cell)
}

private fun drawCoordinates(s: DrawScope, cell: Float, flipped: Boolean, measurer: androidx.compose.ui.text.TextMeasurer, density: Float) {
    val style = TextStyle(fontSize = (cell * 0.2f / density).sp, fontWeight = FontWeight.SemiBold)
    for (i in 0..7) {
        val file = if (flipped) 7 - i else i
        val bottomLight = Square.isLight(squareAt(i, 7, flipped))
        val fileLabel = measurer.measure(('a' + file).toString(), style.copy(color = if (bottomLight) Lens.BoardDark else Lens.BoardLight))
        s.drawText(fileLabel, topLeft = Offset(i * cell + cell - fileLabel.size.width - cell * 0.05f, 8 * cell - fileLabel.size.height))
        val rank = if (flipped) i else 7 - i
        val leftLight = Square.isLight(squareAt(0, i, flipped))
        val rankLabel = measurer.measure(('1' + rank).toString(), style.copy(color = if (leftLight) Lens.BoardDark else Lens.BoardLight))
        s.drawText(rankLabel, topLeft = Offset(cell * 0.05f, i * cell))
    }
}

private fun drawArrow(s: DrawScope, a: BoardArrow, cell: Float, flipped: Boolean) {
    val from = topLeft(a.from, cell, flipped) + Offset(cell / 2, cell / 2)
    val to = topLeft(a.to, cell, flipped) + Offset(cell / 2, cell / 2)
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len < 1f) return
    val ang = atan2(dy, dx)
    val w = a.width * cell
    val head = cell * 0.42f
    val hw = w * 2.3f
    val ux = cos(ang)
    val uy = sin(ang)
    val start = Offset(from.x + ux * cell * 0.2f, from.y + uy * cell * 0.2f)
    val base = Offset(to.x - ux * head, to.y - uy * head)
    val nx = -uy
    val ny = ux
    val path = Path().apply {
        moveTo(start.x + nx * w / 2, start.y + ny * w / 2)
        lineTo(base.x + nx * w / 2, base.y + ny * w / 2)
        lineTo(base.x + nx * hw / 2, base.y + ny * hw / 2)
        lineTo(to.x, to.y)
        lineTo(base.x - nx * hw / 2, base.y - ny * hw / 2)
        lineTo(base.x - nx * w / 2, base.y - ny * w / 2)
        lineTo(start.x - nx * w / 2, start.y - ny * w / 2)
        close()
    }
    s.drawPath(path, a.color.copy(alpha = 0.82f))
    s.drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(cell * 0.02f))
}
