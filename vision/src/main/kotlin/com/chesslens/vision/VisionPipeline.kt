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
    /** Result of a requested position scan (once). */
    val scan: BoardScan? = null,
    /** 0..1 while a scan is collecting still frames; null when no scan is pending. */
    val scanProgress: Float? = null,
    /** How steeply the camera looks down at the board (1 = straight down). */
    val steepness: Float = 1f,
)

/**
 * Orchestrates board finding → tracking → move detection. Call [process] from a single
 * background thread with upright grayscale frames.
 *
 * The board is found and re-anchored from its X-corner lattice ([GridLocator]); optical flow
 * ([BoardTracker]) carries it smoothly between anchors, so a hand-held phone stays aligned.
 */
class VisionPipeline {
    private val grid = GridLocator()
    private val detector = BoardDetector()
    private val tracker = BoardTracker()
    private val moveDetector = MoveDetector()

    /** Debug hook forwarded to the move detector. */
    var debugEval: ((ByteArray, ByteArray) -> Unit)?
        get() = moveDetector.debugEval
        set(v) { moveDetector.debugEval = v }
    private val warpMat = Mat()

    @Volatile private var manualQuad: BoardQuad? = null
    @Volatile private var orientationOverride: Int? = null
    @Volatile private var resyncRequest: MoveDetector.Resync? = null
    @Volatile private var relockRequested = false
    @Volatile private var surfaceRequest: BoardSurface? = null

    /** Current measurement model (physical board vs. a board on a screen). */
    @Volatile
    var surface: BoardSurface = BoardSurface.PHYSICAL
        private set

    var phase = TrackingPhase.SEARCHING
        private set
    var orientation = 0
        private set
    private var orientationKnown = false
    private var frameIndex = 0L
    private var lastScore = 0.0
    private var lastCandidate: BoardQuad? = null
    private var lastLock: BoardQuad? = null
    private var snapFailures = 0

    private var trackMs = 0L
    private var lastSnap = 0f

    /** Per-stage timings of the last frame (debugging). */
    var lastTimings: String = ""
        private set

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

    /** Switch between a physical board and a board shown on a screen. */
    fun setSurface(s: BoardSurface) {
        surface = s
        surfaceRequest = s
    }

    fun relock() {
        relockRequested = true
    }

    /**
     * Reads the position off the board over the next few still frames (see [BoardScanner]).
     * @param anyAngle scan even from a low, oblique view
     */
    fun requestScan(anyAngle: Boolean = false) {
        scanAnyAngle = anyAngle
        scanRequested = true
    }

    fun cancelScan() {
        scanRequested = false
    }

    private val scanner = BoardScanner()
    @Volatile private var scanRequested = false
    @Volatile private var scanAnyAngle = false
    private val scanAcc = Mat()
    private val scanTmp = Mat()
    private var scanCount = 0

    /**
     * @param gray upright grayscale frame (not retained)
     * @param board the position the board currently shows
     * @param autoOrientation work out which side White sits on from the pieces when locking
     */
    /**
     * @param skin optional skin mask aligned with [gray] (see [CvUtils.skinFromChroma]): a hand
     *   over or at the edge of the board blocks move reading even when it holds still
     */
    fun process(gray: Mat, board: Board, autoOrientation: Boolean, skin: Mat? = null): VisionFrame {
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
            lastCandidate = null
            orientationKnown = false
        }
        manualQuad?.let { q ->
            manualQuad = null
            // Snap the hand-placed corners onto the real lattice when it is visible.
            val snapped = grid.locate(gray, prior = q)?.quad?.takeIf { it.alignedTo(q).maxCornerDistance(q) < sqrt(q.area()) * 0.15f }
            lock(snapped?.alignedTo(q) ?: q, gray, autoOrientation, null)
            lastScore = 1.0
        }
        surfaceRequest?.let {
            surfaceRequest = null
            moveDetector.setSurface(it)
        }
        resyncRequest?.let {
            moveDetector.requestResync(it)
            resyncRequest = null
        }

        val t1 = System.nanoTime()
        when (phase) {
            TrackingPhase.SEARCHING, TrackingPhase.LOST -> search(gray, board, autoOrientation)
            TrackingPhase.LOCKED -> track(gray)
        }
        val t2 = System.nanoTime()

        val quad = tracker.quad
        var out = MoveDetector.Output(0.0, false)
        var scan: BoardScan? = null
        var scanProgress: Float? = null
        var steep = 1f
        val hand = phase == TrackingPhase.LOCKED && quad != null && skin != null && handVisible(skin, quad)
        if (phase == TrackingPhase.LOCKED && quad != null) {
            CvUtils.warpBoard(gray, quad, Cells.SIZE, warpMat)
            // Screens add moiré and pixel-grid texture: smooth it before comparing structure.
            if (surface == BoardSurface.SCREEN) org.opencv.imgproc.Imgproc.GaussianBlur(warpMat, warpMat, org.opencv.core.Size(3.0, 3.0), 0.0)
            val px = CvUtils.bytes(warpMat)
            out = moveDetector.process(px, board, orientation, cameraDirection(quad, gray.rows()), visibility(quad, gray.cols(), gray.rows()), quad, hand)
            val cam = if (surface == BoardSurface.SCREEN) Pt(0f, 0f) else BoardScanner.cameraDir(quad, gray.rows())
            steep = if (surface == BoardSurface.SCREEN) 1f else BoardScanner.steepness(quad, cam)
            if (scanRequested) {
                scan = collectScan(quad, cam, steep, out, hand, gray)
                scanProgress = if (scan != null) 1f else scanCount.toFloat() / SCAN_FRAMES
            }
        } else if (scanRequested) {
            scanCount = 0
            scanProgress = 0f
        }
        val t3 = System.nanoTime()
        lastTimings = "${moveDetector.lastNote} skin=${"%.3f".format(lastSkin)}/${"%.3f".format(skinBaseline)} snap=${"%.1f".format(lastSnap)} lk=$trackMs pre=${(t1 - t0) / 1000000} locate/track=${(t2 - t1) / 1000000} moves=${(t3 - t2) / 1000000} grid[${grid.lastTimings}]"
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
            scan = scan,
            scanProgress = scanProgress,
            steepness = steep,
        )
    }

    /** Averages still frames of the rectified board; scans once enough are in. */
    private fun collectScan(quad: BoardQuad, cam: Pt, steep: Float, out: MoveDetector.Output, hand: Boolean, gray: Mat): BoardScan? {
        val still = !out.handOverBoard && !hand && out.motion <= moveDetector.tuning.motionThreshold
        if (!still || (steep < BoardScan.MIN_STEEPNESS && !scanAnyAngle)) {
            scanCount = 0
            return null
        }
        warpMat.convertTo(scanTmp, org.opencv.core.CvType.CV_32F)
        if (scanCount == 0) scanTmp.copyTo(scanAcc) else org.opencv.core.Core.add(scanAcc, scanTmp, scanAcc)
        scanCount++
        if (scanCount < SCAN_FRAMES) return null
        scanAcc.convertTo(scanTmp, org.opencv.core.CvType.CV_8U, 1.0 / scanCount)
        val px = CvUtils.bytes(scanTmp)
        scanCount = 0
        scanRequested = false
        return scanner.scan(px, orientation, cam, visibleCells(quad, gray.cols(), gray.rows()), steep)
    }

    /** Board-space cells (row-major) entirely inside the frame. */
    private fun visibleCells(q: BoardQuad, w: Int, h: Int): BooleanArray {
        val m = 2f
        val hm = q.boardToImage
        return BooleanArray(64) { c ->
            val col = c % 8
            val row = c / 8
            listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1).all { (dx, dy) ->
                val p = hm.map((col + dx).toFloat(), (row + dy).toFloat())
                p.x > m && p.y > m && p.x < w - m && p.y < h - m
            }
        }
    }

    private fun search(gray: Mat, board: Board, autoOrientation: Boolean) {
        val wasLost = phase == TrackingPhase.LOST
        val prior = if (wasLost) lastLock else null
        var found = grid.locate(gray, prior = prior, expected = board.squares)
        if (found == null && prior != null) found = grid.locate(gray, expected = board.squares)
        var quad = found?.quad
        if (quad == null && frameIndex % 4 == 0L) quad = detector.detect(gray)?.quad
        if (quad == null) {
            lastCandidate = null
            return
        }
        lastScore = found?.score ?: 0.5
        val prev = lastCandidate
        lastCandidate = quad
        // Two consecutive agreeing frames lock (fast enough for a hand-held phone).
        if (prev == null || quad.alignedTo(prev).maxCornerDistance(prev) > sqrt(quad.area()) * 0.08f) return
        val q = if (prior != null) quad.alignedTo(prior) else quad
        lock(q, gray, autoOrientation && !wasLost, found?.orientation)
        if (wasLost) moveDetector.requestResync(MoveDetector.Resync.NOW)
    }

    private fun track(gray: Mat) {
        val tt = System.nanoTime()
        val tracked = tracker.update(gray) ?: return
        trackMs = (System.nanoTime() - tt) / 1000000
        val diag = sqrt(tracked.area())
        // Re-anchor on the lattice often, and on every frame the move detector will compare.
        if (moveDetector.wantsPreciseFrame || frameIndex % 3 == 0L) {
            val r = grid.locate(gray, prior = tracked)
            val snapped = r?.quad?.alignedTo(tracked)
            if (snapped != null && snapped.maxCornerDistance(tracked) < diag * 0.15f) {
                // Sub-pixel differences are noise; re-anchoring on them would make the picture shimmer.
                val d = snapped.maxCornerDistance(tracked)
                lastSnap = d
                if (d > 0.75f) tracker.correct(if (d < 4f) tracked.lerp(snapped, 0.25f) else snapped)
                lastLock = snapped
                lastScore = r.score
                snapFailures = 0
            } else {
                snapFailures++
            }
        }
        if (tracker.lostFrames > 20 || snapFailures > 45) {
            phase = TrackingPhase.LOST
            snapFailures = 0
        }
    }

    private fun lock(q: BoardQuad, gray: Mat, autoOrientation: Boolean, locatorOrientation: Int?) {
        skinBaseline = -1.0
        tracker.reset(q, gray)
        moveDetector.reset()
        lastLock = q
        lastCandidate = null
        snapFailures = 0
        phase = TrackingPhase.LOCKED
        if (autoOrientation || !orientationKnown) {
            val est = locatorOrientation
                ?: OrientationEstimator.estimate(CvUtils.bytes(CvUtils.warpBoard(gray, q, Cells.SIZE, warpMat)))
            when {
                est != null -> {
                    orientation = est
                    orientationKnown = true
                }
                !orientationKnown -> {
                    // Default: White is the side closest to the camera (bottom of the image).
                    orientation = closestEdgeOrientation(q)
                    orientationKnown = true
                }
            }
        }
    }

    private val skinWarp = Mat()
    private var skinBaseline = -1.0

    /**
     * Skin-coloured area over the board and a margin around it, compared with what the board
     * itself shows when no hand is around (wooden boards are skin-coloured too).
     */
    private fun handVisible(skin: Mat, quad: BoardQuad): Boolean {
        CvUtils.warpBoard(skin, quad.inset(-0.08f, -0.08f, -0.08f, -0.08f), 64, skinWarp)
        val frac = org.opencv.core.Core.mean(skinWarp).`val`[0] / 255.0
        if (skinBaseline < 0) skinBaseline = frac
        val hand = frac > skinBaseline + 0.012
        if (!hand) skinBaseline = skinBaseline * 0.95 + frac * 0.05
        lastSkin = frac
        return hand
    }

    private var lastSkin = 0.0

    /** Squares entirely inside the frame (the board may run off the edge). */
    private fun visibility(q: BoardQuad, w: Int, h: Int): BooleanArray {
        val m = 2f
        val hm = q.boardToImage
        return BooleanArray(64) { sq ->
            val c = BoardSpace.squareCenter(sq, orientation)
            listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f).all { (dx, dy) ->
                val p = hm.map(c.x + dx, c.y + dy)
                p.x > m && p.y > m && p.x < w - m && p.y < h - m
            }
        }
    }

    /** Orientation that puts White on the board edge nearest to the bottom of the image. */
    private fun closestEdgeOrientation(q: BoardQuad): Int {
        val c = q.corners
        val edges = listOf(
            (c[3].y + c[2].y) / 2 to 0,
            (c[0].y + c[3].y) / 2 to 1,
            (c[0].y + c[1].y) / 2 to 2,
            (c[1].y + c[2].y) / 2 to 3,
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

    fun release() {
        tracker.release()
        warpMat.release()
        skinWarp.release()
        scanAcc.release()
        scanTmp.release()
    }

    companion object {
        /** Still frames averaged for a position scan. */
        const val SCAN_FRAMES = 6
    }
}
