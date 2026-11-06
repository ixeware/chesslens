package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Piece
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.random.Random

/** Renders simple but realistic-enough board photos for tests. */
object SyntheticBoard {
    private const val TOP = 800
    private const val BORDER = 0.06

    init {
        nu.pattern.OpenCV.loadLocally()
    }

    /**
     * @param quadOuter where the outer edge of the board (incl. border) lands in the image
     * @param orientation which side White sits on in board space (see [BoardSpace])
     * @param hand draws a big blob over the board to simulate a hand
     */
    fun render(
        board: Board,
        quadOuter: BoardQuad,
        orientation: Int = 0,
        width: Int = 640,
        height: Int = 480,
        hand: Boolean = false,
        seed: Int = 1,
        brightness: Double = 0.0,
    ): Mat {
        val rnd = Random(seed)
        Core.setRNGSeed(seed)
        val top = Mat(TOP, TOP, CvType.CV_8UC1, Scalar(222.0))
        val b0 = TOP * BORDER
        val cell = (TOP - 2 * b0) / 8
        for (row in 0..7) for (col in 0..7) {
            val light = com.chesslens.chess.Square.isLight(BoardSpace.squareAtCell(col, row, orientation))
            Imgproc.rectangle(
                top,
                Point(b0 + col * cell, b0 + row * cell),
                Point(b0 + (col + 1) * cell, b0 + (row + 1) * cell),
                Scalar(if (light) 215.0 else 95.0),
                -1,
            )
        }
        for (sq in 0 until 64) {
            val p = board.pieceAt(sq)
            if (p == 0) continue
            val c = BoardSpace.squareCenter(sq, orientation)
            val center = Point(b0 + c.x * cell, b0 + c.y * cell)
            val r = cell * (if (Piece.type(p) == Piece.PAWN) 0.26 else 0.34)
            val white = Piece.color(p) == Piece.WHITE
            Imgproc.circle(top, center, r.toInt(), Scalar(if (white) 250.0 else 25.0), -1)
            Imgproc.circle(top, center, r.toInt(), Scalar(if (white) 60.0 else 170.0), 3)
            Imgproc.circle(top, center, (r * 0.4).toInt(), Scalar(if (white) 200.0 else 70.0), 2)
        }

        val img = Mat(height, width, CvType.CV_8UC1)
        val noise = Mat(height, width, CvType.CV_8UC1)
        Core.randu(noise, 0.0, 60.0)
        img.setTo(Scalar(90.0))
        Core.add(img, noise, img)
        // Wood-like stripes.
        for (y in 0 until height step 7) {
            Imgproc.line(img, Point(0.0, y.toDouble()), Point(width.toDouble(), y + rnd.nextDouble() * 20), Scalar(70.0 + rnd.nextDouble() * 30), 2)
        }

        val src = CvUtils.toMat(listOf(Pt(0f, 0f), Pt(TOP.toFloat(), 0f), Pt(TOP.toFloat(), TOP.toFloat()), Pt(0f, TOP.toFloat())))
        val dst = CvUtils.toMat(quadOuter.corners)
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(top, warped, m, Size(width.toDouble(), height.toDouble()))
        val mask = Mat(TOP, TOP, CvType.CV_8UC1, Scalar(255.0))
        val maskW = Mat()
        Imgproc.warpPerspective(mask, maskW, m, Size(width.toDouble(), height.toDouble()))
        warped.copyTo(img, maskW)

        if (hand) {
            val center = quadOuter.boardToImage.map(4f + rnd.nextFloat(), 5f)
            Imgproc.ellipse(img, Point(center.x.toDouble(), center.y.toDouble()), Size(70.0, 45.0), 20.0, 0.0, 360.0, Scalar(160.0), -1)
            Imgproc.rectangle(img, Point(center.x.toDouble() - 30, center.y.toDouble()), Point(center.x + 40.0, height.toDouble()), Scalar(150.0), -1)
        }
        if (brightness != 0.0) Core.add(img, Scalar(brightness), img)
        val n2 = Mat(height, width, CvType.CV_8UC1)
        Core.randn(n2, 0.0, 4.0)
        Core.add(img, n2, img)
        Imgproc.GaussianBlur(img, img, Size(3.0, 3.0), 0.0)
        top.release(); noise.release(); warped.release(); mask.release(); maskW.release(); n2.release()
        return img
    }

    /** The 8x8 grid corners corresponding to a given outer board quad. */
    fun gridOf(outer: BoardQuad): BoardQuad {
        val f = BORDER.toFloat()
        return outer.inset(f, f, f, f)
    }
}
