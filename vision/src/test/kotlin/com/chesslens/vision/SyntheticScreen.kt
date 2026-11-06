package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Piece
import com.chesslens.chess.Square
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * A board as an online chess site draws it, photographed off a monitor: flat sprites,
 * last-move highlight squares, a bezel, slight blur, sensor noise and refresh flicker.
 */
object SyntheticScreen {
    private const val TOP = 640

    init {
        nu.pattern.OpenCV.loadLocally()
    }

    /** Grid corners (the 8x8 board) in the camera image. */
    val grid = BoardQuad(listOf(Pt(150f, 70f), Pt(500f, 82f), Pt(520f, 420f), Pt(128f, 405f)))

    fun render(board: Board, highlight: Pair<Int, Int>? = null, seed: Int = 1, flicker: Double = 0.0, slide: Triple<Int, Int, Float>? = null): Mat {
        Core.setRNGSeed(seed)
        val top = Mat(TOP, TOP, CvType.CV_8UC1)
        val cell = TOP / 8.0
        for (sq in 0 until 64) {
            val f = Square.file(sq)
            val r = Square.rank(sq)
            val x = f * cell
            val y = (7 - r) * cell
            var v = if (Square.isLight(sq)) 226.0 else 118.0
            if (highlight != null && (sq == highlight.first || sq == highlight.second)) v = if (Square.isLight(sq)) 205.0 else 176.0
            Imgproc.rectangle(top, Point(x, y), Point(x + cell, y + cell), Scalar(v), -1)
        }
        for (sq in 0 until 64) {
            val p = board.pieceAt(sq)
            if (p == 0) continue
            var cx = (Square.file(sq) + 0.5) * cell
            var cy = (7.5 - Square.rank(sq)) * cell
            if (slide != null && slide.second == sq) {
                // Mid-animation: the piece is still travelling from its origin.
                val ox = (Square.file(slide.first) + 0.5) * cell
                val oy = (7.5 - Square.rank(slide.first)) * cell
                cx = ox + (cx - ox) * slide.third
                cy = oy + (cy - oy) * slide.third
            }
            sprite(top, p, cx, cy, cell)
        }
        // Monitor: bezel + screen.
        val w = 640
        val h = 480
        val img = Mat(h, w, CvType.CV_8UC1, Scalar(40.0))
        val bezel = grid.inset(-0.12f, -0.1f, -0.12f, -0.14f)
        Imgproc.fillConvexPoly(img, MatOfPoint(*bezel.corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()), Scalar(22.0))
        val screen = grid.inset(-0.07f, -0.05f, -0.07f, -0.07f)
        Imgproc.fillConvexPoly(img, MatOfPoint(*screen.corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()), Scalar(58.0))
        val src = CvUtils.toMat(listOf(Pt(0f, 0f), Pt(TOP.toFloat(), 0f), Pt(TOP.toFloat(), TOP.toFloat()), Pt(0f, TOP.toFloat())))
        val m = Imgproc.getPerspectiveTransform(src, CvUtils.toMat(grid.corners))
        val warped = Mat()
        Imgproc.warpPerspective(top, warped, m, Size(w.toDouble(), h.toDouble()))
        val mask = Mat()
        Imgproc.warpPerspective(Mat(TOP, TOP, CvType.CV_8UC1, Scalar(255.0)), mask, m, Size(w.toDouble(), h.toDouble()))
        warped.copyTo(img, mask)
        // Faint moiré stripes and refresh flicker.
        for (y in 0 until h step 3) Imgproc.line(img, Point(0.0, y.toDouble()), Point(w.toDouble(), y + 1.0), Scalar(0.0), 1)
        Core.addWeighted(img, 1.0, img, 0.0, flicker, img)
        val noise = Mat(h, w, CvType.CV_8UC1)
        Core.randn(noise, 0.0, 3.0)
        Core.add(img, noise, img)
        Imgproc.GaussianBlur(img, img, Size(3.0, 3.0), 0.0)
        return img
    }

    private fun sprite(img: Mat, piece: Int, cx: Double, cy: Double, cell: Double) {
        val white = Piece.color(piece) == Piece.WHITE
        val fill = Scalar(if (white) 250.0 else 30.0)
        val line = Scalar(if (white) 20.0 else 200.0)
        val s = cell * 0.36
        val base = arrayOf(Point(cx - s, cy + s), Point(cx + s, cy + s), Point(cx + s * 0.7, cy + s * 0.55), Point(cx - s * 0.7, cy + s * 0.55))
        Imgproc.fillConvexPoly(img, MatOfPoint(*base), fill)
        when (Piece.type(piece)) {
            Piece.PAWN -> Imgproc.circle(img, Point(cx, cy - s * 0.15), (s * 0.45).toInt(), fill, -1)
            Piece.KNIGHT -> Imgproc.fillConvexPoly(img, MatOfPoint(Point(cx - s * 0.5, cy + s * 0.6), Point(cx - s * 0.6, cy - s * 0.2), Point(cx + s * 0.2, cy - s * 0.9), Point(cx + s * 0.6, cy + s * 0.6)), fill)
            Piece.BISHOP -> Imgproc.ellipse(img, Point(cx, cy - s * 0.2), Size(s * 0.4, s * 0.7), 0.0, 0.0, 360.0, fill, -1)
            Piece.ROOK -> Imgproc.rectangle(img, Point(cx - s * 0.5, cy - s * 0.8), Point(cx + s * 0.5, cy + s * 0.6), fill, -1)
            Piece.QUEEN -> Imgproc.fillConvexPoly(img, MatOfPoint(Point(cx - s * 0.75, cy - s * 0.8), Point(cx + s * 0.75, cy - s * 0.8), Point(cx + s * 0.45, cy + s * 0.6), Point(cx - s * 0.45, cy + s * 0.6)), fill)
            Piece.KING -> {
                Imgproc.rectangle(img, Point(cx - s * 0.5, cy - s * 0.5), Point(cx + s * 0.5, cy + s * 0.6), fill, -1)
                Imgproc.rectangle(img, Point(cx - s * 0.12, cy - s * 0.95), Point(cx + s * 0.12, cy - s * 0.5), fill, -1)
            }
        }
        Imgproc.circle(img, Point(cx, cy), (s * 1.05).toInt(), line, 2)
    }
}
