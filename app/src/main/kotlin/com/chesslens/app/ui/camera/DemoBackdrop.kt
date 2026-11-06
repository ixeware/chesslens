package com.chesslens.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.chesslens.app.ui.ar.ArProjector
import com.chesslens.app.ui.board.PieceRenderer
import com.chesslens.chess.Square

/** Default demo board placement as fractions of the view (a tilted tabletop shot). */
fun demoQuad(width: Float, height: Float): List<Offset> = listOf(
    Offset(width * 0.25f, height * 0.17f),
    Offset(width * 0.75f, height * 0.17f),
    Offset(width * 0.9f, height * 0.88f),
    Offset(width * 0.1f, height * 0.88f),
)

/**
 * A rendered "camera" scene: wooden table and a vinyl tournament board in perspective with
 * standing pieces. Used for the home hero, demo mode without camera, and screenshots.
 */
@Composable
fun DemoBackdrop(
    squares: IntArray,
    orientation: Int,
    modifier: Modifier = Modifier,
    quadFor: (Float, Float) -> List<Offset> = ::demoQuad,
    onQuad: ((List<Offset>) -> Unit)? = null,
) {
    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { onQuad?.invoke(quadFor(it.width.toFloat(), it.height.toFloat())) },
    ) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF5A3B25), Color(0xFF3A2416))))
        for (i in 0 until 18) {
            val y = size.height * i / 18f
            drawLine(Color(0x22000000), Offset(0f, y), Offset(size.width, y + size.height * 0.03f), strokeWidth = 3f)
        }
        val quad = quadFor(size.width, size.height)
        val pj = ArProjector(quad, orientation)
        // Vinyl sheet with border.
        val border = listOf(Offset(-0.45f, -0.45f), Offset(8.45f, -0.45f), Offset(8.45f, 8.45f), Offset(-0.45f, 8.45f))
        val sheet = Path().apply {
            border.forEachIndexed { i, b ->
                val v = pj.board(b.x, b.y)
                if (i == 0) moveTo(v.x, v.y) else lineTo(v.x, v.y)
            }
            close()
        }
        drawPath(sheet, Color(0xFFF1F3EC))
        for (sq in 0 until 64) {
            drawPath(pj.squarePath(sq), if (Square.isLight(sq)) Color(0xFFF4F6EF) else Color(0xFF1E8E6E))
        }
        // Pieces, far ones first so near pieces overlap them.
        val order = (0 until 64).filter { squares[it] != 0 }.sortedBy { pj.center(it).y }
        for (sq in order) {
            val c = pj.center(sq)
            val s = pj.squareSize(sq) * 1.05f
            drawOval(Color.Black.copy(alpha = 0.25f), topLeft = Offset(c.x - s * 0.3f, c.y - s * 0.08f), size = androidx.compose.ui.geometry.Size(s * 0.6f, s * 0.2f))
            PieceRenderer.draw(this, squares[sq], Offset(c.x - s / 2, c.y - s * 0.88f), s)
        }
    }
}
