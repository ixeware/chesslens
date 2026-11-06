package com.chesslens.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.sqrt

/**
 * Keeps a [BoardQuad] glued to the board while the camera moves: sparse Lucas-Kanade
 * optical flow on texture around the board + RANSAC homography. Moving hands are rejected
 * as outliers because most tracked features sit on the static board/table.
 */
class BoardTracker {
    var quad: BoardQuad? = null
        private set
    private var prev: Mat? = null
    private var prevPts: MatOfPoint2f? = null
    var lostFrames = 0
        private set
    var lastInliers = 0
        private set

    fun reset(q: BoardQuad?, gray: Mat?) {
        quad = q
        prev?.release()
        prev = gray?.clone()
        prevPts?.release()
        prevPts = null
        lostFrames = 0
    }

    /** Snaps the tracked quad to a new estimate (e.g. a fresh detection) without losing features. */
    fun correct(q: BoardQuad) {
        quad = q
    }

    fun update(gray: Mat): BoardQuad? {
        val q = quad ?: return null
        val p = prev
        if (p == null || p.size() != gray.size()) {
            prev?.release()
            prev = gray.clone()
            prevPts?.release()
            prevPts = null
            return q
        }
        var pts = prevPts
        if (pts == null || pts.rows() < 40) {
            pts?.release()
            pts = features(p, q)
            prevPts = pts
        }
        if (pts.rows() < 8) {
            gray.copyTo(p)
            lostFrames++
            return q
        }

        val next = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        Video.calcOpticalFlowPyrLK(p, gray, pts, next, status, err, Size(21.0, 21.0), 3)
        val a = pts.toArray()
        val b = next.toArray()
        val st = status.toArray()
        next.release(); status.release(); err.release()
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        for (i in a.indices) {
            if (st[i].toInt() != 0) {
                src += a[i]
                dst += b[i]
            }
        }
        gray.copyTo(p)
        if (src.size < 8) {
            lostFrames++
            prevPts?.release()
            prevPts = null
            return q
        }
        val srcM = MatOfPoint2f(*src.toTypedArray())
        val dstM = MatOfPoint2f(*dst.toTypedArray())
        val mask = Mat()
        val h = Calib3d.findHomography(srcM, dstM, Calib3d.RANSAC, 2.5, mask)
        srcM.release(); dstM.release()
        if (h.empty()) {
            mask.release()
            lostFrames++
            return q
        }
        val inlierMask = ByteArray(mask.rows())
        mask.get(0, 0, inlierMask)
        mask.release()
        val inliers = ArrayList<Point>()
        for (i in dst.indices) if (inlierMask[i].toInt() != 0) inliers += dst[i]
        lastInliers = inliers.size
        prevPts?.release()
        prevPts = if (inliers.size >= 8) MatOfPoint2f(*inliers.toTypedArray()) else null
        if (inliers.size < 8) {
            h.release()
            lostFrames++
            return q
        }

        val hd = DoubleArray(9)
        h.convertTo(h, CvType.CV_64F)
        h.get(0, 0, hd)
        h.release()
        val hom = Homography(hd)
        val moved = q.map { hom.map(it) }
        val diag = sqrt(q.area().toDouble()).toFloat()
        val shift = moved.maxCornerDistance(q)
        val areaRatio = moved.area() / q.area()
        if (!moved.isConvex() || areaRatio !in 0.7f..1.4f || shift > diag * 0.3f) {
            lostFrames++
            return q
        }
        lostFrames = 0
        // Ignore sub-pixel jitter so the overlay stays rock solid on a static scene.
        if (shift > 0.6f) quad = moved
        return quad
    }

    private fun features(gray: Mat, q: BoardQuad): MatOfPoint2f {
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val c = q.corners
        val cx = c.sumOf { it.x.toDouble() } / 4
        val cy = c.sumOf { it.y.toDouble() } / 4
        // Track the board plus a margin of table around it (also planar & static).
        val expanded = c.map { Point(cx + (it.x - cx) * 1.25, cy + (it.y - cy) * 1.25) }
        Imgproc.fillConvexPoly(mask, MatOfPoint(*expanded.toTypedArray()), Scalar(255.0))
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(gray, corners, 220, 0.01, 7.0, mask)
        mask.release()
        val out = MatOfPoint2f()
        corners.convertTo(out, CvType.CV_32FC2)
        corners.release()
        return out
    }

    fun release() {
        prev?.release()
        prevPts?.release()
        prev = null
        prevPts = null
    }
}
