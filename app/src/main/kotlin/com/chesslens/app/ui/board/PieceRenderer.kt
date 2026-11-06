package com.chesslens.app.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.chesslens.chess.Piece

/** Flat vector chess pieces drawn procedurally in a unit square (no font/asset dependency). */
object PieceRenderer {
    private val whiteFill = Color(0xFFF8F6EF)
    private val whiteLine = Color(0xFF24262A)
    private val blackFill = Color(0xFF2C3033)
    private val blackLine = Color(0xFF0B0C0D)
    private val blackDetail = Color(0xFFB8C0BC)

    private val cache = HashMap<Int, List<Path>>()

    fun draw(scope: DrawScope, piece: Int, topLeft: Offset, size: Float, alpha: Float = 1f) {
        if (piece == 0) return
        val white = Piece.color(piece) == Piece.WHITE
        val parts = cache.getOrPut(Piece.type(piece)) { build(Piece.type(piece)) }
        val fill = (if (white) whiteFill else blackFill).copy(alpha = alpha)
        val line = (if (white) whiteLine else blackLine).copy(alpha = alpha)
        val detail = (if (white) whiteLine else blackDetail).copy(alpha = alpha)
        with(scope) {
            translate(topLeft.x, topLeft.y) {
                scale(size, size, pivot = Offset.Zero) {
                    // Soft drop shadow.
                    translate(0.012f, 0.03f) {
                        for (p in parts.dropLast(1)) drawPath(p, Color.Black.copy(alpha = 0.22f * alpha))
                    }
                    for (p in parts.dropLast(1)) {
                        drawPath(p, fill)
                        drawPath(p, line, style = Stroke(width = 0.035f, join = StrokeJoin.Round, cap = StrokeCap.Round))
                    }
                    drawPath(parts.last(), detail, style = Stroke(width = 0.03f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }

    /** Returns filled shapes followed by one stroked detail path (last element). */
    private fun build(type: Int): List<Path> {
        val base = Path().apply {
            addRoundRect(RoundRect(Rect(0.2f, 0.8f, 0.8f, 0.9f), 0.03f, 0.03f))
        }
        val collar = Path().apply {
            addRoundRect(RoundRect(Rect(0.27f, 0.72f, 0.73f, 0.8f), 0.02f, 0.02f))
        }
        return when (type) {
            Piece.PAWN -> listOf(
                base,
                Path().apply {
                    moveTo(0.36f, 0.8f)
                    cubicTo(0.37f, 0.62f, 0.42f, 0.52f, 0.44f, 0.46f)
                    lineTo(0.56f, 0.46f)
                    cubicTo(0.58f, 0.52f, 0.63f, 0.62f, 0.64f, 0.8f)
                    close()
                },
                Path().apply { addRoundRect(RoundRect(Rect(0.37f, 0.42f, 0.63f, 0.48f), 0.03f, 0.03f)) },
                Path().apply { addOval(Rect(Offset(0.5f, 0.31f), 0.125f)) },
                Path(),
            )
            Piece.ROOK -> listOf(
                base, collar,
                Path().apply {
                    moveTo(0.33f, 0.72f); lineTo(0.36f, 0.38f); lineTo(0.64f, 0.38f); lineTo(0.67f, 0.72f); close()
                },
                Path().apply {
                    moveTo(0.27f, 0.38f); lineTo(0.27f, 0.18f); lineTo(0.37f, 0.18f); lineTo(0.37f, 0.25f)
                    lineTo(0.45f, 0.25f); lineTo(0.45f, 0.18f); lineTo(0.55f, 0.18f); lineTo(0.55f, 0.25f)
                    lineTo(0.63f, 0.25f); lineTo(0.63f, 0.18f); lineTo(0.73f, 0.18f); lineTo(0.73f, 0.38f); close()
                },
                Path().apply { moveTo(0.36f, 0.38f); lineTo(0.64f, 0.38f) },
            )
            Piece.BISHOP -> listOf(
                base, collar,
                Path().apply {
                    moveTo(0.38f, 0.72f); lineTo(0.42f, 0.6f); lineTo(0.58f, 0.6f); lineTo(0.62f, 0.72f); close()
                },
                Path().apply { addRoundRect(RoundRect(Rect(0.35f, 0.56f, 0.65f, 0.62f), 0.03f, 0.03f)) },
                Path().apply {
                    moveTo(0.5f, 0.16f)
                    cubicTo(0.62f, 0.25f, 0.69f, 0.36f, 0.65f, 0.47f)
                    cubicTo(0.63f, 0.53f, 0.58f, 0.56f, 0.5f, 0.56f)
                    cubicTo(0.42f, 0.56f, 0.37f, 0.53f, 0.35f, 0.47f)
                    cubicTo(0.31f, 0.36f, 0.38f, 0.25f, 0.5f, 0.16f)
                    close()
                },
                Path().apply { addOval(Rect(Offset(0.5f, 0.13f), 0.045f)) },
                Path().apply { moveTo(0.55f, 0.28f); lineTo(0.46f, 0.41f) },
            )
            Piece.KNIGHT -> listOf(
                base,
                knight(),
                Path().apply {
                    // Eye and mane line.
                    addOval(Rect(Offset(0.445f, 0.335f), 0.02f))
                    moveTo(0.6f, 0.3f); cubicTo(0.66f, 0.42f, 0.67f, 0.56f, 0.63f, 0.7f)
                },
            )
            Piece.QUEEN -> listOf(
                base, collar,
                Path().apply {
                    moveTo(0.3f, 0.72f); lineTo(0.22f, 0.3f); lineTo(0.37f, 0.5f); lineTo(0.42f, 0.24f)
                    lineTo(0.5f, 0.48f); lineTo(0.58f, 0.24f); lineTo(0.63f, 0.5f); lineTo(0.78f, 0.3f)
                    lineTo(0.7f, 0.72f); close()
                },
                Path().apply {
                    for ((x, y) in listOf(0.22f to 0.27f, 0.42f to 0.21f, 0.58f to 0.21f, 0.78f to 0.27f)) addOval(Rect(Offset(x, y), 0.045f))
                    addOval(Rect(Offset(0.5f, 0.17f), 0.05f))
                },
                Path().apply { moveTo(0.32f, 0.64f); lineTo(0.68f, 0.64f) },
            )
            Piece.KING -> listOf(
                base, collar,
                Path().apply {
                    moveTo(0.3f, 0.72f)
                    lineTo(0.25f, 0.44f)
                    cubicTo(0.3f, 0.32f, 0.44f, 0.34f, 0.5f, 0.42f)
                    cubicTo(0.56f, 0.34f, 0.7f, 0.32f, 0.75f, 0.44f)
                    lineTo(0.7f, 0.72f)
                    close()
                },
                Path().apply {
                    addRect(Rect(0.465f, 0.1f, 0.535f, 0.38f))
                    addRect(Rect(0.4f, 0.17f, 0.6f, 0.235f))
                },
                Path().apply { moveTo(0.31f, 0.62f); lineTo(0.69f, 0.62f) },
            )
            else -> listOf(Path(), Path())
        }
    }

    /** Knight silhouette (same drawing as the launcher icon), mapped into the unit square. */
    private fun knight(): Path {
        // Source box: x 22..72, y 16..86 in a 100-unit canvas.
        fun x(v: Float) = 0.2f + (v - 22f) / 70f * 0.66f
        fun y(v: Float) = 0.12f + (v - 16f) / 70f * 0.7f
        return Path().apply {
            fillType = PathFillType.NonZero
            moveTo(x(28f), y(80f))
            lineTo(x(66f), y(80f))
            cubicTo(x(70f), y(68f), x(72f), y(58f), x(69f), y(48f))
            cubicTo(x(66f), y(36f), x(60f), y(28f), x(55f), y(23f))
            lineTo(x(54f), y(16f))
            lineTo(x(49f), y(22f))
            cubicTo(x(44f), y(22f), x(38f), y(26f), x(33f), y(32f))
            lineTo(x(24f), y(45f))
            cubicTo(x(22f), y(48f), x(23f), y(52f), x(27f), y(53f))
            lineTo(x(31f), y(54f))
            cubicTo(x(34f), y(52f), x(38f), y(50f), x(43f), y(49f))
            cubicTo(x(41f), y(56f), x(36f), y(62f), x(33f), y(70f))
            close()
        }
    }
}
