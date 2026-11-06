package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Piece
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed interface VisionEvent {
    /**
     * One move (or a move and the instant reply, e.g. an online opponent pre-moving) whose
     * square changes match the picture. Moves are legal in sequence from the matched position.
     */
    data class MoveDetected(val moves: List<Int>, val confidence: Float) : VisionEvent {
        val move: Int get() = moves.first()
    }

    /** Something changed on the board but it doesn't match any legal move. */
    data class Unrecognized(val squares: List<Int>) : VisionEvent

    /** Reference image re-captured (after a manual correction / undo). */
    data object Resynced : VisionEvent

    /** The changes look like a piece going [from] → [to], which isn't legal in this position. */
    data class IllegalMove(val from: Int, val to: Int) : VisionEvent

    /**
     * A periodic check found the board showing the app position after undoing [undo] plies and
     * playing [moves] (moves the detector missed or misread).
     */
    data class CaughtUp(val undo: Int, val moves: List<Int>) : VisionEvent

    /** Board and app position disagree on [squares]; an empty list means they agree again. */
    data class PositionMismatch(val squares: List<Int>) : VisionEvent
}

/** What the camera is looking at; tunes how square changes are measured. */
enum class BoardSurface {
    /** A real board with standing pieces: perspective, occlusion by tall pieces, hands. */
    PHYSICAL,

    /** A board drawn on a monitor, tablet or printout: flat sprites, move highlights, cursor. */
    SCREEN,
}

/** Detector parameters per surface. */
data class DetectorTuning(
    /** Fraction of a cell sampled. */
    val patch: Float,
    /** Shift the sample towards the camera, where piece bases stand (physical boards only). */
    val shiftToCamera: Boolean,
    val stableFrames: Int,
    val motionThreshold: Double,
    val changeThreshold: Double,
    /**
     * Compare cells by structure (zero-mean normalised correlation) instead of brightness:
     * immune to square highlight colours that screens paint on the last move.
     */
    val structural: Boolean,
) {
    companion object {
        fun of(surface: BoardSurface) = when (surface) {
            // Small central patch: the piece base is on the board plane, so it barely moves with
            // parallax when a hand-held camera shifts; tall piece bodies do.
            BoardSurface.PHYSICAL -> DetectorTuning(0.45f, false, 4, 8.0, 14.0, structural = false)
            BoardSurface.SCREEN -> DetectorTuning(0.88f, false, 3, 9.0, 13.0, structural = true)
        }
    }
}

/** Per-cell statistics on a rectified [SIZE]x[SIZE] board image (board space, row-major cells). */
object Cells {
    const val SIZE = 256
    const val CELL = SIZE / 8

    private inline fun forPatch(row: Int, col: Int, patch: Float, dx: Float, dy: Float, f: (Int) -> Unit) {
        val half = (CELL * patch / 2).toInt()
        val cx = (col * CELL + CELL / 2 + dx * CELL).toInt()
        val cy = (row * CELL + CELL / 2 + dy * CELL).toInt()
        for (y in (cy - half).coerceAtLeast(0) until (cy + half).coerceAtMost(SIZE)) {
            val base = y * SIZE
            for (x in (cx - half).coerceAtLeast(0) until (cx + half).coerceAtMost(SIZE)) f(base + x)
        }
    }

    /**
     * Mean absolute difference between [a] and [b] inside each cell. The sampled patch covers
     * [patch] of the cell and is shifted by ([dx], [dy]) cell fractions.
     */
    fun absDiff(a: ByteArray, b: ByteArray, patch: Float, dx: Float = 0f, dy: Float = 0f, slack: Int = 0): DoubleArray {
        val out = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) {
            var best = Double.MAX_VALUE
            // Tolerate a pixel or two of misregistration between the pictures.
            for (sy in -slack..slack) for (sx in -slack..slack) {
                val off = sy * SIZE + sx
                var sum = 0L
                var n = 0
                forPatch(row, col, patch, dx, dy) { i ->
                    val j = i + off
                    if (j in 0 until SIZE * SIZE) {
                        sum += abs((a[i].toInt() and 0xFF) - (b[j].toInt() and 0xFF))
                        n++
                    }
                }
                if (n > 0) best = minOf(best, sum.toDouble() / n)
            }
            out[row * 8 + col] = if (best == Double.MAX_VALUE) 0.0 else best
        }
        return out
    }

    /**
     * Structural change per cell on a 0..~60 scale comparable to [absDiff]:
     * flat→flat (empty, whatever its colour) = 0, flat↔textured (piece appeared or left) = high,
     * textured→textured = 1 − correlation of the zero-mean patterns (different piece = high,
     * same piece under a highlight = low).
     */
    fun structureChange(a: ByteArray, b: ByteArray, patch: Float, flat: Double = 7.0, slack: Int = 0): DoubleArray {
        val out = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) {
            // Empty or occupied is decided on the aligned patch; only the pattern comparison
            // of two occupied cells searches a small shift.
            val first = structureCell(a, b, row, col, patch, flat, 0)
            if (first.second) {
                out[row * 8 + col] = first.first
                continue
            }
            var best = first.first
            for (sy in -slack..slack) for (sx in -slack..slack) {
                if (sx == 0 && sy == 0) continue
                best = minOf(best, structureCell(a, b, row, col, patch, flat, sy * SIZE + sx).first)
            }
            out[row * 8 + col] = best
        }
        return out
    }

    /** (change, decided-by-flatness). */
    private fun structureCell(a: ByteArray, b: ByteArray, row: Int, col: Int, patch: Float, flat: Double, off: Int): Pair<Double, Boolean> {
        var n = 0
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        forPatch(row, col, patch, 0f, 0f) { i ->
            val j = i + off
            if (j in 0 until SIZE * SIZE) {
                val va = (a[i].toInt() and 0xFF).toDouble()
                val vb = (b[j].toInt() and 0xFF).toDouble()
                sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
                n++
            }
        }
        if (n == 0) return 0.0 to true
        val ma = sa / n
        val mb = sb / n
        val sdA = sqrt(max(0.0, saa / n - ma * ma))
        val sdB = sqrt(max(0.0, sbb / n - mb * mb))
        val flatA = sdA < flat
        val flatB = sdB < flat
        return when {
            flatA && flatB -> 0.0 to true
            flatA != flatB -> (40.0 + abs(sdA - sdB) * 0.25) to true
            else -> {
                val zncc = (sab / n - ma * mb) / (sdA * sdB)
                (1.0 - zncc).coerceIn(0.0, 2.0) * 45.0 to false
            }
        }
    }

    /** Mean gradient magnitude (outline energy) per cell: picks up a white pawn on a light square. */
    fun energy(a: ByteArray, patch: Float, dx: Float = 0f, dy: Float = 0f): DoubleArray {
        val out = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) {
            var sum = 0L
            var n = 0
            forPatch(row, col, patch, dx, dy) { i ->
                val x = i % SIZE
                val y = i / SIZE
                if (x in 1 until SIZE - 1 && y in 1 until SIZE - 1) {
                    val gx = (a[i + 1].toInt() and 0xFF) - (a[i - 1].toInt() and 0xFF)
                    val gy = (a[i + SIZE].toInt() and 0xFF) - (a[i - SIZE].toInt() and 0xFF)
                    sum += abs(gx) + abs(gy)
                    n++
                }
            }
            out[row * 8 + col] = if (n == 0) 0.0 else sum.toDouble() / n
        }
        return out
    }

    fun meanAndStd(a: ByteArray, patch: Float): Pair<DoubleArray, DoubleArray> {
        val mean = DoubleArray(64)
        val std = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) {
            var s = 0.0
            var s2 = 0.0
            var n = 0
            forPatch(row, col, patch, 0f, 0f) { i ->
                val v = (a[i].toInt() and 0xFF).toDouble()
                s += v; s2 += v * v; n++
            }
            val m = s / n
            mean[row * 8 + col] = m
            std[row * 8 + col] = sqrt(max(0.0, s2 / n - m * m))
        }
        return mean to std
    }
}

/**
 * Detects moves by comparing the rectified board after the scene settles with a reference
 * picture of the last known position, and matching the changed squares against legal moves
 * (and, when nothing fits, against a move plus the opponent's instant reply).
 * No piece classifier is needed: the game state comes from the rules.
 */
class MoveDetector(surface: BoardSurface = BoardSurface.PHYSICAL) {
    enum class Resync { NONE, NOW, AFTER_MOTION }

    data class Output(
        val motion: Double,
        val handOverBoard: Boolean,
        val event: VisionEvent? = null,
        /** Per-square change magnitudes (a1 = 0) from the last evaluation, for debugging/FX. */
        val squareChanges: DoubleArray? = null,
    )

    var tuning: DetectorTuning = DetectorTuning.of(surface)
        private set

    private var ref: ByteArray? = null
    private var refVisible: BooleanArray? = null
    /** While true the reference follows the idle scene, so it's taken from the latest viewpoint. */
    private var refFollows = true

    /** Recent idle pictures with the board's position in the image (the viewpoint they show). */
    private class Shot(val px: ByteArray, val visible: BooleanArray?, val view: BoardQuad?)
    private val history = ArrayDeque<Shot>()
    private var view: BoardQuad? = null

    /** Debug hook: (current, reference) rectified pictures at each evaluation. */
    var debugEval: ((ByteArray, ByteArray) -> Unit)? = null

    /** Debug: how the last evaluation went. */
    var lastNote: String = ""
        private set
    private var prev: ByteArray? = null
    private var stableCount = 0
    private var sawMotion = false
    private var resync = Resync.NONE

    /** Switches the measurement model; the reference is re-captured on the next still frame. */
    fun setSurface(surface: BoardSurface) {
        val t = DetectorTuning.of(surface)
        if (t == tuning) return
        tuning = t
        resync = Resync.NOW
    }

    fun reset() {
        refFollows = true
        history.clear()
        ref = null
        prev = null
        stableCount = 0
        sawMotion = false
        resync = Resync.NONE
    }

    fun requestResync(mode: Resync) {
        resync = mode
    }

    /** Replaces the reference (and previous frame) with [warped] right away. */
    fun setReference(warped: ByteArray) {
        setRef(warped)
        prev = warped
    }

    private fun seenNow(s: Int): Boolean = (visible?.get(s) ?: true) && (refVisible?.get(s) ?: true)

    private fun setRef(warped: ByteArray, keepHistory: Boolean = false) {
        ref = warped
        refVisible = visible?.copyOf()
        if (!keepHistory) history.clear()
        history.addLast(Shot(warped, refVisible, view))
        while (history.size > 45) history.removeFirst()
    }

    private fun closestShots(n: Int): List<Shot> {
        val cur = view ?: return emptyList()
        val diag = sqrt(cur.area()).coerceAtLeast(1f)
        return history.filter { it.view != null }
            .sortedBy { it.view!!.maxCornerDistance(cur) / diag }
            .take(n)
            .filter { it.view!!.maxCornerDistance(cur) / diag <= MAX_VIEW_SHIFT }
    }

    /** Idle picture whose viewpoint is closest to the current one, and how far it is. */
    private fun closestShot(): Pair<Shot?, Float> {
        val cur = view ?: return null to 0f
        var best: Shot? = null
        var bestD = Float.MAX_VALUE
        val diag = sqrt(cur.area()).coerceAtLeast(1f)
        for (s in history) {
            val v = s.view ?: continue
            val d = v.maxCornerDistance(cur) / diag
            if (d < bestD) { bestD = d; best = s }
        }
        return best to (if (best == null) 0f else bestD)
    }

    /** The next frame is compared against the reference: worth re-anchoring the grid precisely. */
    val wantsPreciseFrame: Boolean
        get() = ref == null || resync != Resync.NONE || (sawMotion && stableCount >= tuning.stableFrames - 2)

    /** Squares currently in view (a1 = 0); out-of-frame squares are ignored by [evaluate]. */
    private var visible: BooleanArray? = null
    private var orientation = 0
    private var cameraDir = Pt(0f, 0.15f)

    /** True when the scene has been still since the last reference capture. */
    val isIdle: Boolean get() = !sawMotion && stableCount >= tuning.stableFrames && resync == Resync.NONE && ref != null

    /**
     * @param warped rectified board ([Cells.SIZE]² bytes)
     * @param board position the board is believed to show (before the next move)
     * @param orientation board-space rotation (see [BoardSpace])
     * @param cameraDir direction towards the camera in board space (cell fractions)
     */
    fun process(
        warped: ByteArray,
        board: Board,
        orientation: Int,
        cameraDir: Pt = Pt(0f, 0.15f),
        visible: BooleanArray? = null,
        view: BoardQuad? = null,
        handSeen: Boolean = false,
    ): Output {
        this.visible = visible
        this.view = view
        this.orientation = orientation
        this.cameraDir = cameraDir
        val t = tuning
        val p = prev
        prev = warped
        if (p == null) {
            if (ref == null) setRef(warped)
            return Output(0.0, false)
        }
        val motion = motionLevel(warped, p, board, orientation)
        if (motion > t.motionThreshold || handSeen) {
            stableCount = 0
            sawMotion = true
            return Output(motion, true)
        }
        stableCount++
        val r = ref
        if (r == null) {
            setRef(warped)
            return Output(motion, false)
        }
        if (stableCount < t.stableFrames) return Output(motion, sawMotion)

        if (resync == Resync.NOW || (resync == Resync.AFTER_MOTION && sawMotion && stableCount == t.stableFrames)) {
            setRef(warped)
            resync = Resync.NONE
            sawMotion = false
            return Output(motion, false, VisionEvent.Resynced)
        }

        if (stableCount == t.stableFrames && sawMotion) {
            sawMotion = false
            // Compare with the idle picture taken from the most similar viewpoint.
            val (shot, shift) = closestShot()
            if (shot != null && shift > MAX_VIEW_SHIFT) {
                lastNote = "view moved ${"%.2f".format(shift)}: re-anchored"
                setRef(warped)
                refFollows = true
                return Output(motion, false)
            }
            if (shot != null) refVisible = shot.visible
            // Per square, the smallest change against the few closest viewpoints: parallax
            // differs from shot to shot, a moved piece shows up in all of them.
            val shots = closestShots(3).ifEmpty { listOf(Shot(r, refVisible, view)) }
            debugEval?.let { cb -> shots.forEach { cb(warped, it.px) } }
            val sq = DoubleArray(64) { Double.MAX_VALUE }
            for (sh in shots) {
                val c = squareChanges(warped, sh.px, orientation, cameraDir)
                for (i in 0 until 64) sq[i] = minOf(sq[i], c[i])
            }
            val event = evaluate(sq, board)
            val bef = board.squares
            val unexplained = (0 until 64).count { sq[it] > barFor(it, bef) && seenNow(it) }
            lastNote = "view shift ${"%.2f".format(shift)} unexplained=$unexplained"
            when {
                event is VisionEvent.MoveDetected -> { setRef(warped); refFollows = true }
                event is VisionEvent.Unrecognized || event is VisionEvent.IllegalMove -> refFollows = false // keep the pre-move picture
                unexplained <= 1 -> { setRef(warped); refFollows = true }
                unexplained > 6 && shift >= 0.2f -> { setRef(warped); refFollows = true } // the view changed
                unexplained > 6 -> {
                    // Many squares changed from the same viewpoint: a hand is resting over the
                    // board. Keep the pre-move picture and wait for it to leave.
                    lastNote += " hand resting"
                    sawMotion = true
                    stableCount = 0
                    return Output(motion, true, null, sq)
                }
                else -> refFollows = false
            }
            return Output(motion, false, event, sq)
        }
        // While nothing happens, keep the reference fresh: a hand-held camera keeps moving and
        // tall pieces shift with parallax, so "before" must be taken just before the hand comes.
        if (refFollows && !sawMotion && stableCount > t.stableFrames && stableCount % 2 == 0) setRef(warped, keepHistory = true)
        return Output(motion, false)
    }

    /**
     * Frame-to-frame activity. Empty squares are flat and planar, so they only change when
     * something (a hand) passes over them; occupied squares also change with parallax as a
     * hand-held camera moves, so they count with a much higher bar.
     */
    private fun motionLevel(cur: ByteArray, prev: ByteArray, board: Board, orientation: Int): Double {
        val cells = Cells.absDiff(cur, prev, 0.6f, slack = 1)
        val vis = visible
        var empty = 0.0
        var occupied = 0.0
        for (row in 0..7) for (col in 0..7) {
            val sq = BoardSpace.squareAtCell(col, row, orientation)
            if (vis != null && !vis[sq]) continue
            val v = cells[row * 8 + col]
            if (board.pieceAt(sq) == 0) empty = max(empty, v) else occupied = max(occupied, v)
        }
        // Screens have no parallax: any change is activity (e.g. a piece sliding over others).
        return if (tuning.structural) max(empty, occupied) else max(empty, occupied / 3.0)
    }

    /** Per-square (a1 = 0) change between [cur] and [ref] under the current tuning. */
    fun squareChanges(cur: ByteArray, ref: ByteArray, orientation: Int, cameraDir: Pt): DoubleArray {
        val t = tuning
        val cells = if (t.structural) {
            Cells.structureChange(cur, ref, t.patch, slack = 2)
        } else {
            // Sample the part of each square away from the camera: the piece on the square in
            // front leans into the near part of this one, and how far depends on the viewpoint.
            val away = Pt(-cameraDir.x, -cameraDir.y)
            val raw = Cells.absDiff(cur, ref, t.patch, away.x, away.y, slack = 1)
            val median = raw.sorted()[32]
            val eCur = Cells.energy(cur, t.patch, away.x, away.y)
            val eRef = Cells.energy(ref, t.patch, away.x, away.y)
            DoubleArray(64) { max(raw[it] - median, abs(eCur[it] - eRef[it]) * 1.3) }
        }
        val sq = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) sq[BoardSpace.squareAtCell(col, row, orientation)] = max(0.0, cells[row * 8 + col])
        return sq
    }

    /**
     * Bar a square must clear to count as an unexplained change. On a physical board, a square
     * that holds a piece, or stands right behind an occupied square, changes a little whenever a
     * hand-held camera moves (parallax), so it needs more. Occupancy before or after the
     * candidate move counts (a piece that just arrived casts its own "shadow").
     */
    private fun barFor(sq: Int, before: IntArray, after: IntArray? = null): Double {
        val base = tuning.changeThreshold
        if (tuning.structural) return base
        fun occ(s: Int) = before[s] != 0 || (after != null && after[s] != 0)
        var k = 1.0
        if (occ(sq)) k = max(k, 1.8)
        val front = squareTowardCamera(sq)
        if (front >= 0 && occ(front)) k = max(k, 1.7)
        return base * k
    }

    /** The neighbouring square one step away from the camera, or null off the board. */
    private fun squareAwayFromCamera(sq: Int): Int? = stepFromCamera(sq, -1).takeIf { it >= 0 }

    /** The neighbouring square one step towards the camera, or -1 off the board. */
    private fun squareTowardCamera(sq: Int): Int = stepFromCamera(sq, 1)

    private fun stepFromCamera(sq: Int, sign: Int): Int {
        val c = BoardSpace.squareCenter(sq, orientation)
        val dx = cameraDir.x * sign
        val dy = cameraDir.y * sign
        val (sx, sy) = if (abs(dx) > abs(dy)) (if (dx > 0) 1 else -1) to 0 else 0 to (if (dy > 0) 1 else -1)
        val col = c.x.toInt() + sx
        val row = c.y.toInt() + sy
        if (col !in 0..7 || row !in 0..7) return -1
        return BoardSpace.squareAtCell(col, row, orientation)
    }

    private class Cand(val moves: List<Int>, val score: Double, val minIn: Double, val finalKey: Long)

    /** Picks the move (or move + reply) that best explains per-square changes [c]. */
    fun evaluate(c: DoubleArray, board: Board): VisionEvent? {
        val threshold = tuning.changeThreshold
        val before = board.squares.copyOf()
        val visibleNow = (0 until 64).filter { seenNow(it) }
        // Squares that changed beyond what parallax alone explains (before-position bars).
        val strong = visibleNow.filter { c[it] > barFor(it, before) }
        val weak = visibleNow.filter { c[it] > threshold * 0.85 }
        if (weak.isEmpty()) return null
        if (strong.size > 12) return null // camera moved or hand still in view

        fun score(expectedAll: List<Int>, after: IntArray): Pair<Double, Double> {
            // Squares outside the frame can't confirm or contradict a move.
            val expected = expectedAll.filter { seenNow(it) }
            if (expected.isEmpty()) return Double.NEGATIVE_INFINITY to 0.0
            var minIn = Double.MAX_VALUE
            var sumIn = 0.0
            for (s in expected) {
                minIn = minOf(minIn, c[s])
                sumIn += c[s]
            }
            // On a real board a piece that appears or leaves also changes the square just
            // behind it (its body leans there in the picture): that change is explained.
            val shadows = if (tuning.structural) emptyList() else expectedAll.mapNotNull { squareAwayFromCamera(it) }
                .filter { it !in expectedAll && before[it] == 0 && after[it] == 0 }
            var maxExcess = 0.0
            for (s in visibleNow) {
                if (s in expectedAll || s in shadows) continue
                maxExcess = max(maxExcess, c[s] - barFor(s, before, after))
            }
            var shadowBonus = 0.0
            for (s in shadows) if (seenNow(s) && c[s] > threshold * 0.6) shadowBonus += c[s] * 0.15
            // A resting hand changes several squares the move can't account for.
            val unexplained = strong.count { it !in expectedAll && it !in shadows && c[it] > barFor(it, before, after) }
            if (unexplained > 2) return Double.NEGATIVE_INFINITY to minIn
            return (minIn * 0.6 + sumIn / expected.size * 0.4 - maxExcess * 1.2 + shadowBonus) to minIn
        }

        fun pick(cands: List<Cand>): Cand? {
            // Candidates reaching the same position are the same answer (e.g. promotion pieces).
            val best = cands.groupBy { it.finalKey }.map { (_, g) -> g.maxBy { it.score } }.sortedByDescending { it.score }
            val top = best.firstOrNull() ?: return null
            val second = best.getOrNull(1)?.score ?: Double.NEGATIVE_INFINITY
            val ok = top.minIn > threshold * 0.85 && top.score > threshold * 0.6 && top.score - second > threshold * 0.2
            return if (ok) top else null
        }

        val singles = ArrayList<Cand>()
        val work = board.copy()
        val legal = work.legalMoves()
        for (m in legal) {
            work.makeMove(m)
            val (s, minIn) = score(diff(before, work.squares), work.squares)
            singles += Cand(listOf(m), preferQueen(m, s), minIn, work.key)
            work.unmakeMove()
        }
        val single = pick(singles)

        // A move and an immediate reply (online opponents, pre-moves). Screens only: on a real
        // board each move is made by hand, and a hand lingering at the edge would otherwise be
        // read as a second move.
        if (tuning.structural && weak.size >= 3) {
            val pairs = ArrayList<Cand>()
            for (m1 in legal) {
                work.makeMove(m1)
                for (m2 in work.legalMoves()) {
                    work.makeMove(m2)
                    val (s, minIn) = score(diff(before, work.squares), work.squares)
                    pairs += Cand(listOf(m1, m2), preferQueen(m1, preferQueen(m2, s)), minIn, work.key)
                    work.unmakeMove()
                }
                work.unmakeMove()
            }
            val pair = pick(pairs)
            if (pair != null && (single == null || pair.score > single.score + threshold * 0.3)) {
                return VisionEvent.MoveDetected(pair.moves, confidence(pair, pairs))
            }
        }
        single?.let { return VisionEvent.MoveDetected(it.moves, confidence(it, singles)) }
        if (strong.isEmpty() || strong.size > 6) return null
        // Nothing legal fits: does a piece making a move it could physically make (path clear),
        // that is only wrong because of whose turn it is or a king left in check, explain it?
        // Anything else is a hand mid-move or a misread, not worth calling illegal.
        val touched = (strong + weak).distinct()
        val physical = HashSet<Int>()
        val pseudo = IntArray(256)
        for (flip in 0..1) {
            val b = board.copy()
            if (flip == 1) b.makeNullMove()
            val n = b.generatePseudo(pseudo)
            for (i in 0 until n) physical += (com.chesslens.chess.Move.from(pseudo[i]) shl 6) or com.chesslens.chess.Move.to(pseudo[i])
        }
        var illegal: Pair<Int, Int>? = null
        var illegalScore = Double.NEGATIVE_INFINITY
        for (from in touched) {
            val p = before[from]
            if (p == 0) continue
            for (to in touched) {
                if (to == from) continue
                if ((from shl 6) or to !in physical) continue
                val q = before[to]
                if (q != 0 && Piece.color(q) == Piece.color(p)) continue
                val after = before.copyOf()
                after[to] = p
                after[from] = 0
                val (sc, minIn) = score(listOf(from, to), after)
                // Clear changes on both squares and nothing else unexplained.
                val others = strong.count { it != from && it != to && c[it] > barFor(it, before, after) }
                if (others == 0 && minIn > threshold * 1.2 && sc > threshold * 0.8 && sc > illegalScore) {
                    illegalScore = sc
                    illegal = from to to
                }
            }
        }
        illegal?.let { return VisionEvent.IllegalMove(it.first, it.second) }
        return VisionEvent.Unrecognized(strong)
    }

    private fun preferQueen(m: Int, s: Double): Double {
        val promo = com.chesslens.chess.Move.promo(m)
        return if (promo != 0 && promo != Piece.QUEEN) s - 1e-3 else s
    }

    private fun confidence(top: Cand, all: List<Cand>): Float {
        val second = all.filter { it.finalKey != top.finalKey }.maxOfOrNull { it.score } ?: 0.0
        return ((top.score - max(0.0, second)) / (top.score + 1.0)).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** Beyond this viewpoint change (fraction of the board size) parallax swamps a move. */
        const val MAX_VIEW_SHIFT = 0.3f

        /** Squares whose contents differ between two piece arrays. */
        fun diff(a: IntArray, b: IntArray): List<Int> {
            val out = ArrayList<Int>(4)
            for (i in 0 until 64) if (a[i] != b[i]) out += i
            return out
        }
    }
}

/** Guesses which side of the rectified board White sits on, assuming the start position. */
object OrientationEstimator {
    /** Returns orientation 0..3 or null when the picture doesn't look like a start position. */
    fun estimate(warped: ByteArray): Int? {
        val (mean, std) = Cells.meanAndStd(warped, 0.7f)
        val rowE = DoubleArray(8) { r -> (0..7).sumOf { std[r * 8 + it] } / 8 }
        val colE = DoubleArray(8) { c -> (0..7).sumOf { std[it * 8 + c] } / 8 }
        fun axisScore(e: DoubleArray) = (e[0] + e[1] + e[6] + e[7]) / 4 - (e[2] + e[3] + e[4] + e[5]) / 4
        val rowsScore = axisScore(rowE)
        val colsScore = axisScore(colE)
        if (max(rowsScore, colsScore) < 2.0) return null
        return if (rowsScore >= colsScore) {
            val top = (0..1).sumOf { r -> (0..7).sumOf { mean[r * 8 + it] } }
            val bottom = (6..7).sumOf { r -> (0..7).sumOf { mean[r * 8 + it] } }
            if (bottom >= top) 0 else 2
        } else {
            val left = (0..7).sumOf { r -> mean[r * 8] + mean[r * 8 + 1] }
            val right = (0..7).sumOf { r -> mean[r * 8 + 6] + mean[r * 8 + 7] }
            if (left >= right) 1 else 3
        }
    }
}
