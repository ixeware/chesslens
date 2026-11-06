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

/** Board placement on the demo monitor (a slight keystone, as when filming a screen). */
fun demoScreenQuad(width: Float, height: Float): List<Offset> {
    val s = minOf(width * 0.5f, height * 0.7f)
    val cx = width / 2
    val cy = height * 0.53f
    return listOf(
        Offset(cx - s * 0.48f, cy - s * 0.5f),
        Offset(cx + s * 0.5f, cy - s * 0.48f),
        Offset(cx + s * 0.52f, cy + s * 0.5f),
        Offset(cx - s * 0.5f, cy + s * 0.49f),
    )
}

/** A chess site on a monitor, filmed by the phone: the 2D-screen demo scene. */
@Composable
fun DemoScreenBackdrop(
    squares: IntArray,
    orientation: Int,
    modifier: Modifier = Modifier,
    onQuad: ((List<Offset>) -> Unit)? = null,
) {
    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { onQuad?.invoke(demoScreenQuad(it.width.toFloat(), it.height.toFloat())) },
    ) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1A1D22), Color(0xFF0E1013))))
        val quad = demoScreenQuad(size.width, size.height)
        val pj = ArProjector(quad, orientation, flat = true)
        fun poly(pts: List<Offset>): Path = Path().apply {
            pts.forEachIndexed { i, b -> val v = pj.board(b.x, b.y); if (i == 0) moveTo(v.x, v.y) else lineTo(v.x, v.y) }
            close()
        }
        // Bezel, screen and the site's chrome around the board.
        drawPath(poly(listOf(Offset(-5.2f, -2.2f), Offset(13.2f, -2.2f), Offset(13.2f, 10.6f), Offset(-5.2f, 10.6f))), Color(0xFF050506))
        drawPath(poly(listOf(Offset(-4.8f, -1.8f), Offset(12.8f, -1.8f), Offset(12.8f, 10.2f), Offset(-4.8f, 10.2f))), Color(0xFF302E2B))
        drawPath(poly(listOf(Offset(0f, -1.2f), Offset(4.5f, -1.2f), Offset(4.5f, -0.3f), Offset(0f, -0.3f))), Color(0xFF3D3A37))
        drawPath(poly(listOf(Offset(0f, 8.3f), Offset(4.5f, 8.3f), Offset(4.5f, 9.2f), Offset(0f, 9.2f))), Color(0xFF3D3A37))
        drawPath(poly(listOf(Offset(8.6f, 0f), Offset(12.2f, 0f), Offset(12.2f, 8f), Offset(8.6f, 8f))), Color(0xFF262421))
        for (sq in 0 until 64) drawPath(pj.squarePath(sq), if (Square.isLight(sq)) Color(0xFFEBECD0) else Color(0xFF739552))
        for (sq in 0 until 64) {
            if (squares[sq] == 0) continue
            val c = pj.center(sq)
            val s = pj.squareSize(sq) * 0.92f
            PieceRenderer.draw(this, squares[sq], Offset(c.x - s / 2, c.y - s / 2), s)
        }
        // Screen glare.
        drawPath(
            poly(listOf(Offset(-4.8f, -1.8f), Offset(12.8f, -1.8f), Offset(12.8f, 10.2f), Offset(-4.8f, 10.2f))),
            Brush.linearGradient(listOf(Color(0x14FFFFFF), Color.Transparent, Color(0x0AFFFFFF)), start = Offset.Zero, end = Offset(size.width, size.height)),
        )
    }
}
