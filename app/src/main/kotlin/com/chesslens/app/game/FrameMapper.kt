package com.chesslens.app.game

import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import com.chesslens.vision.Pt

/**
 * Converts between the upright, downscaled analysis image used by the vision pipeline and
 * PreviewView coordinates (via CameraX's sensor-to-view transform).
 */
class FrameMapper(
    private val rawWidth: Int,
    private val rawHeight: Int,
    private val rotation: Int,
    /** Analysis downscale factor applied before rotation. */
    private val scale: Float,
    /** Analysis buffer (unrotated ImageProxy pixels) -> PreviewView coordinates. */
    bufferToView: Matrix,
) {
    private val toView = Matrix(bufferToView)
    private val fromView = Matrix().also { bufferToView.invert(it) }
    private val w = rawWidth * scale
    private val h = rawHeight * scale

    private fun uprightToRaw(x: Float, y: Float): FloatArray {
        val (u, v) = when (rotation) {
            90 -> y to h - x
            180 -> w - x to h - y
            270 -> w - y to x
            else -> x to y
        }
        return floatArrayOf(u / scale, v / scale)
    }

    private fun rawToUpright(u0: Float, v0: Float): Pt {
        val u = u0 * scale
        val v = v0 * scale
        return when (rotation) {
            90 -> Pt(h - v, u)
            180 -> Pt(w - u, h - v)
            270 -> Pt(v, w - u)
            else -> Pt(u, v)
        }
    }

    fun toView(p: Pt): Offset {
        val pts = uprightToRaw(p.x, p.y)
        toView.mapPoints(pts)
        return Offset(pts[0], pts[1])
    }

    fun fromView(o: Offset): Pt {
        val pts = floatArrayOf(o.x, o.y)
        fromView.mapPoints(pts)
        return rawToUpright(pts[0], pts[1])
    }

    companion object {
        fun scaleFor(width: Int, height: Int, maxSide: Int): Float =
            minOf(1f, maxSide.toFloat() / maxOf(width, height))
    }
}
