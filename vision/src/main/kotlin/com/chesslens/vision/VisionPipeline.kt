package com.chesslens.vision

import com.chesslens.chess.Board
import org.opencv.core.Mat
import kotlin.math.hypot
import kotlin.math.sqrt

enum class TrackingPhase { SEARCHING, LOCKED, LOST }

/** Everything the UI needs from one analysed camera frame. */
data class VisionFrame(
    /** Upright analysis image size; [quad] is in these coordinates. */
    val imageWidth: Int,
    val imageHeight: Int,
    val quad: BoardQuad?,
    val phase: TrackingPhase,
    val orientation: Int,
    val handOverBoard: Boolean,
    val motion: Float,
    val event: VisionEvent?,
    val detectionScore: Double,
    val processingMs: Long,
    /** Per-square change magnitudes from the last move evaluation (a1 = 0), if any. */
    val squareChanges: DoubleArray? = null,
)

/**
 * Orchestrates detection → tracking → move detection. Call [process] from a single
 * background thread with upright grayscale frames.
 */
class VisionPipeline {
    private val detector = BoardDetector()
    private val tracker = BoardTracker()
    private val moveDetector = MoveDetector()
    private val warpMat = Mat()

    @Volatile private var manualQuad: BoardQuad? = null
    @Volatile private var orientationOverride: Int? = null
    @Volatile private var resyncRequest: MoveDetector.Resync? = null
    @Volatile private var relockRequested = false

    var phase = TrackingPhase.SEARCHING
        private set
    var orientation = 0
        private set
    private var orientationKnown = false
    private var frameIndex = 0L
    private var lastScore = 0.0
    private val confirm = ArrayList<BoardQuad>()

    /** User dragged corners: lock onto them immediately. */
    fun setManualQuad(q: BoardQuad) {
        manualQuad = q
    }

    fun setOrientation(o: Int) {
        orientationOverride = o and 3
    }

    fun requestResync(afterMotion: Boolean) {
        resyncRequest = if (afterMotion) MoveDetector.Resync.AFTER_MOTION else MoveDetector.Resync.NOW
    }

    fun relock() {
        relockRequested = true
    }

    /**
     * @param gray upright grayscale frame (not retained)
     * @param board the position the physical board currently shows
     * @param autoOrientation estimate orientation from piece layout when locking (start position)
     */
    fun process(gray: Mat, board: Board, autoOrientation: Boolean): VisionFrame {
        val t0 = System.nanoTime()
        frameIndex++
        orientationOverride?.let {
            orientation = it
            orientationKnown = true
            orientationOverride = null
        }
        if (relockRequested) {
            relockRequested = false
            phase = TrackingPhase.SEARCHING
            tracker.reset(null, null)
            moveDetector.reset()
            confirm.clear()
            orientationKnown = false
        }
        manualQuad?.let { q ->
            manualQuad = null
            lock(q, gray, board, autoOrientation)
            lastScore = 1.0
        }
        resyncRequest?.let {
            moveDetector.requestResync(it)
            resyncRequest = null
        }

        var corrected = false
        when (phase) {
            TrackingPhase.SEARCHING, TrackingPhase.LOST -> {
                val r = detector.detect(gray)
                if (r != null) {
                    lastScore = r.score
                    val diag = sqrt(r.quad.area())
                    if (confirm.isNotEmpty() && confirm.last().maxCornerDistance(r.quad) > diag * 0.04f) confirm.clear()
                    confirm += r.quad
                    if (confirm.size >= 3) {
                        val avg = averageQuad(confirm)
                        confirm.clear()
                        val wasLost = phase == TrackingPhase.LOST
                        lock(avg, gray, board, autoOrientation && !wasLost)
                        if (wasLost) moveDetector.requestResync(MoveDetector.Resync.NOW)
                    }
                } else {
                    confirm.clear()
                }
            }
            TrackingPhase.LOCKED -> {
                tracker.update(gray)

                if (frameIndex % 45 == 0L) {
                    val cur = tracker.quad
                    val r = detector.detect(gray)
                    if (r != null && cur != null) {
                        lastScore = r.score
                        val diag = sqrt(cur.area())
                        val d = r.quad.maxCornerDistance(cur)
                        // Only correct drift while nothing is happening on the board, and then
                        // re-capture the reference so the shift doesn't read as a move.
                        if (d < diag * 0.06f && d > 1.0f && moveDetector.isIdle) {
                            tracker.correct(cur.lerp(r.quad, 0.5f))
                            corrected = true
                        }
                    }
                }
                if (tracker.lostFrames > 20) {
                    phase = TrackingPhase.LOST
                }
            }
        }

        val quad = tracker.quad
        var out = MoveDetector.Output(0.0, false)
        if (phase == TrackingPhase.LOCKED && quad != null) {
            CvUtils.warpBoard(gray, quad, Cells.SIZE, warpMat)
            val px = CvUtils.bytes(warpMat)
            if (corrected) moveDetector.setReference(px)
            out = moveDetector.process(px, board, orientation, cameraDirection(quad, gray.rows()))
        }
        return VisionFrame(
            imageWidth = gray.cols(),
            imageHeight = gray.rows(),
            quad = if (phase == TrackingPhase.SEARCHING) null else quad,
            phase = phase,
            orientation = orientation,
            handOverBoard = out.handOverBoard,
            motion = out.motion.toFloat(),
            event = out.event,
            detectionScore = lastScore,
            processingMs = (System.nanoTime() - t0) / 1_000_000,
            squareChanges = out.squareChanges,
        )
    }

    private fun lock(q: BoardQuad, gray: Mat, board: Board, autoOrientation: Boolean) {
        tracker.reset(q, gray)
        moveDetector.reset()
        phase = TrackingPhase.LOCKED
        if (autoOrientation || !orientationKnown) {
            val warped = CvUtils.bytes(CvUtils.warpBoard(gray, q, Cells.SIZE, warpMat))
            val est = OrientationEstimator.estimate(warped)
            if (est != null && (autoOrientation || !orientationKnown)) {
                orientation = est
                orientationKnown = true
            } else if (!orientationKnown) {
                // Default: White is the side closest to the camera (bottom of the image).
                orientation = closestEdgeOrientation(q)
                orientationKnown = true
            }
        }
    }

    /** Orientation that puts White on the board edge nearest to the bottom of the image. */
    private fun closestEdgeOrientation(q: BoardQuad): Int {
        val c = q.corners
        val edges = listOf(
            (c[3].y + c[2].y) / 2 to 0, // bottom edge in board space
            (c[0].y + c[3].y) / 2 to 1, // left edge
            (c[0].y + c[1].y) / 2 to 2, // top edge
            (c[1].y + c[2].y) / 2 to 3, // right edge
        )
        return edges.maxBy { it.first }.second
    }

    /** Direction (in cell fractions) pointing towards the camera inside board space. */
    private fun cameraDirection(q: BoardQuad, imageHeight: Int): Pt {
        val inv = q.imageToBoard
        val cx = q.corners.sumOf { it.x.toDouble() }.toFloat() / 4
        val cy = q.corners.sumOf { it.y.toDouble() }.toFloat() / 4
        val a = inv.map(cx, cy)
        val b = inv.map(cx, (cy + imageHeight * 0.05f))
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-4f) return Pt(0f, 0f)
        return Pt(dx / len * 0.15f, dy / len * 0.15f)
    }

    private fun averageQuad(list: List<BoardQuad>): BoardQuad = BoardQuad(
        (0..3).map { i ->
            Pt(list.map { it.corners[i].x }.average().toFloat(), list.map { it.corners[i].y }.average().toFloat())
        },
    )

    fun release() {
        tracker.release()
        warpMat.release()
    }
}
