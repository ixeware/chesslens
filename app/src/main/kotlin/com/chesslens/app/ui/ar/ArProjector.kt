package com.chesslens.app.ui.ar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.chesslens.vision.BoardQuad
import com.chesslens.vision.BoardSpace
import com.chesslens.vision.Homography
import com.chesslens.vision.Pt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Projects chess coordinates onto the camera preview. Chess space: x = 0..8 (a→h),
 * y = 0..8 (rank 1→8). Board space and orientation follow [BoardSpace].
 */
class ArProjector(val viewQuad: List<Offset>, val orientation: Int) {
    private val h = Homography.fromPoints(BoardQuad.BOARD_CORNERS, viewQuad.map { Pt(it.x, it.y) })
    private val inv = h.inverse()

    fun board(u: Float, v: Float): Offset {
        val p = h.map(u, v)
        return Offset(p.x, p.y)
    }

    fun chess(x: Float, y: Float): Offset {
        val b = BoardSpace.fromChess(x, y, orientation)
        return board(b.x, b.y)
    }

    fun chess(p: Offset): Offset = chess(p.x, p.y)

    fun center(sq: Int): Offset = chess((sq and 7) + 0.5f, (sq shr 3) + 0.5f)

    fun toChess(view: Offset): Offset {
        val b = inv.map(view.x, view.y)
        val c = BoardSpace.toChess(b.x, b.y, orientation)
        return Offset(c.x, c.y)
    }

    fun squareAt(view: Offset): Int? {
        val c = toChess(view)
        if (c.x < 0 || c.y < 0 || c.x >= 8 || c.y >= 8) return null
        return c.y.toInt() * 8 + c.x.toInt()
    }

    /** Approximate on-screen size of one square around [sq]. */
    fun squareSize(sq: Int): Float {
        val f = (sq and 7).toFloat()
        val r = (sq shr 3).toFloat()
        val a = chess(f, r + 0.5f)
        val b = chess(f + 1, r + 0.5f)
        val c = chess(f + 0.5f, r)
        val d = chess(f + 0.5f, r + 1)
        return (hypot(b.x - a.x, b.y - a.y) + hypot(d.x - c.x, d.y - c.y)) / 2
    }

    fun squarePath(sq: Int, inset: Float = 0f): Path {
        val f = (sq and 7).toFloat()
        val r = (sq shr 3).toFloat()
        return polygon(
            listOf(
                Offset(f + inset, r + inset), Offset(f + 1 - inset, r + inset),
                Offset(f + 1 - inset, r + 1 - inset), Offset(f + inset, r + 1 - inset),
            ),
        )
    }

    /** Polygon given in chess coordinates. */
    fun polygon(points: List<Offset>, close: Boolean = true): Path {
        val p = Path()
        points.forEachIndexed { i, c ->
            val v = chess(c)
            if (i == 0) p.moveTo(v.x, v.y) else p.lineTo(v.x, v.y)
        }
        if (close) p.close()
        return p
    }

    /** Circle lying on the board plane (appears as a perspective ellipse). */
    fun circle(cx: Float, cy: Float, radius: Float, segments: Int = 36): Path {
        val pts = (0 until segments).map {
            val a = 2 * PI * it / segments
            Offset(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat())
        }
        return polygon(pts)
    }

    /** Board outline corners in view coordinates (board space order). */
    val corners: List<Offset> get() = listOf(board(0f, 0f), board(8f, 0f), board(8f, 8f), board(0f, 8f))
}
