package com.chesslens.vision

import org.opencv.core.Mat
import kotlin.math.max
import kotlin.math.min

/**
 * Watches the strip just outside the 8x8 grid (board frame and the table around it): an arm
 * reaching for the board has to cross it. Changes there against a reference taken while
 * nothing happens mean a hand is around, even while it holds still over the board.
 *
 * Grey levels only: under warm indoor light a grey board and skin have nearly the same colour.
 * The reference is re-taken after every accepted move and a blob that stays put for a few
 * seconds is absorbed, so the watch can't get stuck reporting a hand.
 */
class EdgeWatch {
    private var ref: FloatArray? = null
    private var refMin: FloatArray? = null
    private var refMax: FloatArray? = null
    private var refValid: BooleanArray? = null
    private var rebaseRequested = false
    private var heldFrames = 0

    /** Fraction of the strip that changed in the last frame (debugging). */
    var lastFraction = 0.0
        private set

    fun reset() {
        ref = null
        heldFrames = 0
        rebaseRequested = false
    }

    /** Takes the next frame as the new reference (call after a move is accepted). */
    fun rebase() {
        rebaseRequested = true
    }

    /**
     * @param still the scene was still in the previous frame (no hand moving over the board)
     * @return true while something covers part of the strip around the board
     */
    fun update(gray: Mat, quad: BoardQuad, still: Boolean): Boolean {
        val w = gray.cols()
        val h = gray.rows()
        if (pixels.size < w * h) pixels = ByteArray(w * h)
        gray.get(0, 0, pixels)
        val hm = quad.boardToImage
        val n = SAMPLES.size
        val cur = FloatArray(n)
        val curMin = FloatArray(n)
        val curMax = FloatArray(n)
        val valid = BooleanArray(n)
        for (k in 0 until n) {
            val (u, v) = SAMPLES[k]
            val p = hm.map(u, v)
            val x = p.x.toInt()
            val y = p.y.toInt()
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) continue
            valid[k] = true
            var lo = 255
            var hi = 0
            for (dy in -1..1) for (dx in -1..1) {
                val g = pixels[(y + dy) * w + x + dx].toInt() and 0xFF
                lo = min(lo, g)
                hi = max(hi, g)
            }
            cur[k] = (pixels[y * w + x].toInt() and 0xFF).toFloat()
            curMin[k] = lo.toFloat()
            curMax[k] = hi.toFloat()
        }
        val r = ref
        if (r == null || rebaseRequested) {
            if (still || rebaseRequested) setRef(cur, curMin, curMax, valid)
            rebaseRequested = false
            lastFraction = 0.0
            return false
        }
        val rMin = refMin!!
        val rMax = refMax!!
        val rValid = refValid!!
        // Exposure drifts: compare after matching the strip's overall brightness.
        var sc = 0.0
        var sr = 0.0
        var both = 0
        for (k in 0 until n) if (valid[k] && rValid[k]) { sc += cur[k]; sr += r[k]; both++ }
        if (both < n / 4) {
            // Mostly out of frame now: start over from this view.
            setRef(cur, curMin, curMax, valid)
            lastFraction = 0.0
            return false
        }
        val gain = if (sc > 1) (sr / sc).toFloat().coerceIn(0.7f, 1.4f) else 1f
        var changed = 0
        for (k in 0 until n) {
            if (!valid[k] || !rValid[k]) continue
            val c = cur[k] * gain
            // Tolerate a pixel of misregistration: compare with the reference's 3x3 range.
            val d = max(0f, max(c - rMax[k], rMin[k] - c))
            if (d > CHANGE) changed++
        }
        val frac = changed.toDouble() / both
        lastFraction = frac
        val hand = frac > HAND_FRACTION
        if (!hand) {
            heldFrames = 0
            if (still) follow(cur, curMin, curMax, valid, gain)
        } else if (still) {
            // Something has been sitting on the strip for a while: part of the scene now.
            if (++heldFrames > ABSORB_FRAMES) {
                setRef(cur, curMin, curMax, valid)
                heldFrames = 0
                return false
            }
        }
        return hand
    }

    private var pixels = ByteArray(0)

    private fun setRef(cur: FloatArray, lo: FloatArray, hi: FloatArray, valid: BooleanArray) {
        ref = cur.copyOf()
        refMin = lo.copyOf()
        refMax = hi.copyOf()
        refValid = valid.copyOf()
        heldFrames = 0
    }

    private fun follow(cur: FloatArray, lo: FloatArray, hi: FloatArray, valid: BooleanArray, gain: Float) {
        val r = ref!!
        val rMin = refMin!!
        val rMax = refMax!!
        val rValid = refValid!!
        for (k in r.indices) {
            if (!valid[k]) continue
            if (!rValid[k]) {
                r[k] = cur[k]; rMin[k] = lo[k]; rMax[k] = hi[k]; rValid[k] = true
                continue
            }
            r[k] += (cur[k] * gain - r[k]) * RATE
            rMin[k] += (lo[k] * gain - rMin[k]) * RATE
            rMax[k] += (hi[k] * gain - rMax[k]) * RATE
        }
    }

    companion object {
        /** Grey-level change (beyond the reference's local range) that counts. */
        private const val CHANGE = 28f
        private const val HAND_FRACTION = 0.06
        private const val RATE = 0.1f
        /** ~4 s at 30 fps. */
        private const val ABSORB_FRAMES = 120

        /** Sample points in board space (cells): a band from 0.1 to 0.8 cells outside the grid. */
        private val SAMPLES: List<Pair<Float, Float>> = buildList {
            val along = 64
            val across = listOf(0.1f, 0.25f, 0.4f, 0.55f, 0.7f, 0.8f)
            for (i in 0 until along) {
                val t = -0.8f + (8f + 1.6f) * (i + 0.5f) / along
                for (d in across) {
                    add(t to -d) // top
                    add(t to 8f + d) // bottom
                    add(-d to t) // left
                    add(8f + d to t) // right
                }
            }
        }.filter { (u, v) -> !(u in 0f..8f && v in 0f..8f) }.distinct()
    }
}
