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

/**
 * Reusable per-frame buffers for one camera thread: avoids allocating megabytes per frame,
 * which on a phone means garbage-collector pauses (visible as stutter).
 */
class FrameBuffers {
    internal var plane = ByteArray(0)
    internal var row = ByteArray(0)
    internal var rowU = ByteArray(0)
    internal var rowV = ByteArray(0)
    internal var chroma = ByteArray(0)
    internal val raw = Mat()
    internal val small = Mat()

    internal fun bytes(cur: ByteArray, n: Int): ByteArray = if (cur.size >= n) cur else ByteArray(n)

    fun release() {
        raw.release()
        small.release()
    }
}

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
        buffers: FrameBuffers? = null,
    ): Mat {
        val raw = buffers?.raw ?: Mat()
        raw.create(height, width, CvType.CV_8UC1)
        buffer.rewind()
        if (rowStride == width && buffer.remaining() >= width * height) {
            val all = buffers?.let { b -> b.bytes(b.plane, width * height).also { b.plane = it } } ?: ByteArray(width * height)
            buffer.get(all, 0, width * height)
            raw.put(0, 0, all, 0, width * height)
        } else {
            val row = buffers?.let { b -> b.bytes(b.row, width).also { b.row = it } } ?: ByteArray(width)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, width)
                raw.put(y, 0, row, 0, width)
            }
        }
        val scale = maxSide.toDouble() / max(width, height)
        val scaled = Mat()
        if (scale < 1.0) {
            Imgproc.resize(raw, scaled, Size(width * scale, height * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            raw.copyTo(scaled)
        }
        if (buffers == null) raw.release()
        return rotate(scaled, rotationDegrees)
    }

    /**
     * Skin mask (255 = skin-coloured) from the chroma planes of a YUV_420_888 frame, scaled and
     * rotated exactly like [grayFromPlane] so it lines up with the grey image. Uses the classic
     * YCbCr skin box (Cb 77–127, Cr 133–173).
     */
    fun skinFromChroma(
        u: ByteBuffer,
        v: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int,
        maxSide: Int = 640,
        buffers: FrameBuffers? = null,
    ): Mat {
        val cw = width / 2
        val ch = height / 2
        val out = buffers?.let { b -> b.bytes(b.chroma, cw * ch).also { b.chroma = it } } ?: ByteArray(cw * ch)
        val rowU = buffers?.let { b -> b.bytes(b.rowU, rowStride).also { b.rowU = it } } ?: ByteArray(rowStride)
        val rowV = buffers?.let { b -> b.bytes(b.rowV, rowStride).also { b.rowV = it } } ?: ByteArray(rowStride)
        for (y in 0 until ch) {
            u.position(y * rowStride)
            v.position(y * rowStride)
            val n = minOf(rowStride, u.remaining(), v.remaining())
            u.get(rowU, 0, n)
            v.get(rowV, 0, n)
            for (x in 0 until cw) {
                val i = x * pixelStride
                if (i >= n) {
                    out[y * cw + x] = 0
                    continue
                }
                val cb = rowU[i].toInt() and 0xFF
                val cr = rowV[i].toInt() and 0xFF
                out[y * cw + x] = if (cb in 77..127 && cr in 133..173) 255.toByte() else 0
            }
        }
        val small = buffers?.small ?: Mat()
        small.create(ch, cw, CvType.CV_8UC1)
        small.put(0, 0, out, 0, cw * ch)
        val scale = maxSide.toDouble() / max(width, height)
        val s = if (scale < 1.0) scale else 1.0
        val sized = Mat()
        Imgproc.resize(small, sized, Size(width * s, height * s), 0.0, 0.0, Imgproc.INTER_NEAREST)
        if (buffers == null) small.release()
        return rotate(sized, rotationDegrees)
    }

    /** Skin mask from an upright BGR image (tests and offline replays). */
    fun skinFromBgr(bgr: Mat): Mat {
        val ycrcb = Mat()
        Imgproc.cvtColor(bgr, ycrcb, Imgproc.COLOR_BGR2YCrCb)
        val mask = Mat()
        org.opencv.core.Core.inRange(ycrcb, org.opencv.core.Scalar(0.0, 133.0, 77.0), org.opencv.core.Scalar(255.0, 173.0, 127.0), mask)
        ycrcb.release()
        return mask
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
