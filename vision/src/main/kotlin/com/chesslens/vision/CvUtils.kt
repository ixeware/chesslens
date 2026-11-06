package com.chesslens.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.max

/** Helpers shared by the vision components. */
object CvUtils {

    /**
     * Builds an upright grayscale Mat from a luminance plane (e.g. CameraX Y plane).
     * The longer side is downscaled to at most [maxSide] pixels.
     */
    fun grayFromPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
        maxSide: Int = 640,
    ): Mat {
        val raw = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        buffer.rewind()
        if (rowStride == width && buffer.remaining() >= width * height) {
            val all = ByteArray(width * height)
            buffer.get(all)
            raw.put(0, 0, all)
        } else {
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, width)
                raw.put(y, 0, row)
            }
        }
        val scale = maxSide.toDouble() / max(width, height)
        val scaled = if (scale < 1.0) {
            val s = Mat()
            Imgproc.resize(raw, s, Size(width * scale, height * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            raw.release()
            s
        } else {
            raw
        }
        return rotate(scaled, rotationDegrees)
    }

    fun rotate(src: Mat, degrees: Int): Mat {
        val code = when (((degrees % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return src
        }
        val dst = Mat()
        Core.rotate(src, dst, code)
        src.release()
        return dst
    }

    fun toMat(points: List<Pt>): MatOfPoint2f = MatOfPoint2f(*points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())

    /** Warps the board region of [gray] into a [size]x[size] top-down image (board space scaled). */
    fun warpBoard(gray: Mat, quad: BoardQuad, size: Int, out: Mat = Mat()): Mat {
        val src = toMat(quad.corners)
        val s = size.toFloat()
        val dst = toMat(listOf(Pt(0f, 0f), Pt(s, 0f), Pt(s, s), Pt(0f, s)))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        Imgproc.warpPerspective(gray, out, m, Size(size.toDouble(), size.toDouble()), Imgproc.INTER_LINEAR)
        src.release(); dst.release(); m.release()
        return out
    }

    fun bytes(mat: Mat): ByteArray {
        val cont = if (mat.isContinuous) mat else mat.clone()
        val out = ByteArray((cont.total() * cont.channels()).toInt())
        cont.get(0, 0, out)
        if (cont !== mat) cont.release()
        return out
    }
}

/** Summed-area table over an unsigned 8-bit image stored as a ByteArray. */
class Integral(private val w: Int, private val h: Int, pixels: ByteArray) {
    private val s = LongArray((w + 1) * (h + 1))

    init {
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += pixels[y * w + x].toInt() and 0xFF
                s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + rowSum
            }
        }
    }

    /** Mean over [x0,x1)x[y0,y1) (clamped). */
    fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val ax = x0.coerceIn(0, w)
        val ay = y0.coerceIn(0, h)
        val bx = x1.coerceIn(0, w)
        val by = y1.coerceIn(0, h)
        val area = (bx - ax) * (by - ay)
        if (area <= 0) return 0.0
        val sum = s[by * (w + 1) + bx] - s[ay * (w + 1) + bx] - s[by * (w + 1) + ax] + s[ay * (w + 1) + ax]
        return sum.toDouble() / area
    }
}
