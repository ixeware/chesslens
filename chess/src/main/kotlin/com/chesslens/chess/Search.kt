package com.chesslens.chess

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One principal variation reported by the engine. Scores are from the side to move's view. */
data class EngineLine(
    val moves: List<Int>,
    val scoreCp: Int,
    /** Moves until mate; positive = side to move mates, negative = gets mated. */
    val mateIn: Int?,
) {
    val move: Int get() = moves.firstOrNull() ?: Move.NONE
}

data class EngineInfo(
    val depth: Int,
    val selDepth: Int,
    val nodes: Long,
    val timeMs: Long,
    val lines: List<EngineLine>,
    /** Side to move in the analysed position; needed to convert scores to White's view. */
    val sideToMove: Int,
    val fen: String,
    val source: String = "Local",
) {
    val best: EngineLine? get() = lines.firstOrNull()

    /** Score of the best line in centipawns from White's perspective (mates clamp to ±10000). */
    fun whiteCp(line: EngineLine? = best): Int {
        line ?: return 0
        val cp = if (line.mateIn != null) (if (line.mateIn > 0) 10000 else -10000) else line.scoreCp
        return if (sideToMove == Piece.WHITE) cp else -cp
    }

    fun whiteMate(line: EngineLine? = best): Int? {
        val m = line?.mateIn ?: return null
        return if (sideToMove == Piece.WHITE) m else -m
    }
}

data class SearchLimits(
    val maxDepth: Int = 64,
    val timeMs: Long = Long.MAX_VALUE,
    val multiPv: Int = 1,
    val nodes: Long = Long.MAX_VALUE,
)

/**
 * Alpha-beta engine: iterative deepening, aspiration windows, PVS, transposition table,
 * null-move pruning, LMR, futility/LMP, killer + history ordering, quiescence search and
 * MultiPV. One instance per thread.
 */
class Search(ttSizeLog2: Int = 20) {
    private val tt = TranspositionTable(ttSizeLog2)

    @Volatile
    private var stopFlag = false

    private lateinit var b: Board
    private var nodes = 0L
    private var selDepth = 0
    private var startNs = 0L
    private var deadlineNs = Long.MAX_VALUE
    private var nodeLimit = Long.MAX_VALUE
    private var aborted = false
    private val rootExcluded = HashSet<Int>()
    private var rootPreferred = Move.NONE

    private val killers = Array(MAX_PLY) { IntArray(2) }
    private val history = Array(64) { IntArray(64) }
    private val pvTable = Array(MAX_PLY + 1) { IntArray(MAX_PLY + 1) }
    private val pvLen = IntArray(MAX_PLY + 1)
    private val moveBuf = Array(MAX_PLY + 1) { IntArray(256) }
    private val scoreBuf = Array(MAX_PLY + 1) { IntArray(256) }

    fun stop() {
        stopFlag = true
    }

    fun clearHash() = tt.clear()

    /**
     * Searches [root] and reports every completed iteration to [onInfo].
     * Returns the last completed iteration (or an empty info for mate/stalemate positions).
     */
    fun run(root: Board, limits: SearchLimits, onInfo: (EngineInfo) -> Unit = {}): EngineInfo {
        b = root.copy()
        stopFlag = false
        aborted = false
        nodes = 0
        startNs = System.nanoTime()
        deadlineNs = if (limits.timeMs == Long.MAX_VALUE) Long.MAX_VALUE else startNs + limits.timeMs * 1_000_000
        nodeLimit = limits.nodes
        for (k in killers) k.fill(0)
        for (h in history) h.fill(0)
        tt.newSearch()

        val fen = root.toFen()
        val rootMoves = b.legalMoves()
        var last = EngineInfo(0, 0, 0, 0, emptyList(), root.side, fen)
        if (rootMoves.isEmpty()) return last
        val multiPv = limits.multiPv.coerceIn(1, rootMoves.size)
        var prevBest = 0

        for (depth in 1..limits.maxDepth.coerceAtMost(MAX_PLY - 8)) {
            val lines = ArrayList<EngineLine>(multiPv)
            rootExcluded.clear()
            selDepth = 0
            for (pvIdx in 0 until multiPv) {
                rootPreferred = if (pvIdx == 0) last.best?.move ?: Move.NONE else Move.NONE
                val score = if (pvIdx == 0 && depth >= 5 && abs(prevBest) < MATE_BOUND) {
                    aspiration(depth, prevBest)
                } else {
                    negamax(depth, -INF, INF, 0, true)
                }
                if (aborted) break
                if (pvLen[0] == 0) break
                val pv = extendPv(root, pvTable[0].copyOfRange(0, pvLen[0]).toList(), depth)
                lines += EngineLine(pv, score, mateIn(score))
                rootExcluded += pv[0]
                if (pvIdx == 0) prevBest = score
            }
            if (aborted && depth > 1) break
            if (lines.isEmpty()) break
            lines.sortByDescending { it.scoreCp }
            val elapsed = (System.nanoTime() - startNs) / 1_000_000
            last = EngineInfo(depth, max(selDepth, depth), nodes, elapsed, lines, root.side, fen)
            onInfo(last)
            if (aborted) break
            if (limits.timeMs != Long.MAX_VALUE && elapsed > limits.timeMs * 55 / 100) break
            val best = lines[0]
            if (best.mateIn != null && depth >= abs(best.mateIn) * 2 + 4 && limits.timeMs != Long.MAX_VALUE) break
        }
        return last
    }

    /** Lengthens a PV truncated by hash cut-offs by following hash moves. */
    private fun extendPv(root: Board, pv: List<Int>, depth: Int): List<Int> {
        if (pv.size >= depth) return pv
        val bd = root.copy()
        val out = ArrayList<Int>(depth)
        for (m in pv) {
            if (!bd.makeMove(m)) return pv
            out += m
        }
        val seen = HashSet<Long>()
        while (out.size < depth) {
            if (!seen.add(bd.key)) break
            val e = tt.probe(bd.key)
            if (e == 0L) break
            val m = TranspositionTable.move(e)
            if (m == Move.NONE || m !in bd.legalMoves()) break
            bd.makeMove(m)
            out += m
        }
        return out
    }

    private fun aspiration(depth: Int, prev: Int): Int {
        var delta = 25
        var alpha = max(prev - delta, -INF)
        var beta = min(prev + delta, INF)
        while (true) {
            val score = negamax(depth, alpha, beta, 0, true)
            if (aborted) return score
            if (score <= alpha) {
                alpha = max(score - delta, -INF)
            } else if (score >= beta) {
                beta = min(score + delta, INF)
            } else {
                return score
            }
            delta += delta / 2 + 10
            if (delta > 1000) {
                alpha = -INF
                beta = INF
            }
        }
    }

    private fun checkLimits() {
        if (stopFlag || nodes >= nodeLimit || System.nanoTime() >= deadlineNs) aborted = true
    }

    private fun negamax(depthIn: Int, alphaIn: Int, beta: Int, ply: Int, allowNull: Boolean): Int {
        pvLen[ply] = ply
        if ((++nodes and 1023L) == 0L) checkLimits()
        if (aborted) return 0
        var alpha = alphaIn
        var depth = depthIn
        val isRoot = ply == 0
        val pvNode = beta - alpha > 1

        if (!isRoot) {
            if (b.halfmoveClock >= 100 || b.isRepetition() || b.isInsufficientMaterial()) return 0
            // Mate distance pruning.
            alpha = max(alpha, -MATE + ply)
            val beta2 = min(beta, MATE - ply - 1)
            if (alpha >= beta2) return alpha
        }
        if (ply >= MAX_PLY - 2) return Eval.evaluate(b)

        val inCheck = b.inCheck()
        if (inCheck) depth++
        if (depth <= 0) return quiesce(alpha, beta, ply)
        if (ply > selDepth) selDepth = ply

        // Transposition table probe.
        val key = b.key
        val entry = tt.probe(key)
        var ttMove = Move.NONE
        if (entry != 0L) {
            ttMove = TranspositionTable.move(entry)
            if (!pvNode && !isRoot && TranspositionTable.depth(entry) >= depth) {
                val s = scoreFromTt(TranspositionTable.score(entry), ply)
                when (TranspositionTable.flag(entry)) {
                    TranspositionTable.EXACT -> return s
                    TranspositionTable.LOWER -> if (s >= beta) return s
                    TranspositionTable.UPPER -> if (s <= alpha) return s
                }
            }
        }
        if (isRoot && rootPreferred != Move.NONE) ttMove = rootPreferred

        val staticEval = if (inCheck) -INF else Eval.evaluate(b)

        if (!pvNode && !inCheck && !isRoot) {
            // Reverse futility pruning.
            if (depth <= 6 && abs(beta) < MATE_BOUND && staticEval - 90 * depth >= beta) return staticEval
            // Null-move pruning.
            if (allowNull && depth >= 3 && staticEval >= beta && b.hasNonPawnMaterial(b.side)) {
                val r = 3 + depth / 6
                b.makeNullMove()
                val s = -negamax(depth - 1 - r, -beta, -beta + 1, ply + 1, false)
                b.unmakeNullMove()
                if (aborted) return 0
                if (s >= beta) return if (s >= MATE_BOUND) beta else s
            }
        }

        val moves = moveBuf[ply]
        val scores = scoreBuf[ply]
        val n = b.generatePseudo(moves)
        scoreMoves(moves, scores, n, ttMove, ply)

        var best = -INF
        var bestMove = Move.NONE
        var legal = 0
        var quietsTried = 0
        for (i in 0 until n) {
            pickNext(moves, scores, i, n)
            val m = moves[i]
            if (isRoot && m in rootExcluded) continue
            val quiet = Move.isQuiet(m)
            if (!b.makeMove(m)) continue
            legal++
            val givesCheck = b.inCheck()

            if (!pvNode && !inCheck && !isRoot && quiet && !givesCheck && legal > 1 && best > -MATE_BOUND) {
                // Late move pruning and futility pruning for quiet moves.
                if (depth <= 4 && quietsTried > 3 + depth * depth * 2) {
                    b.unmakeMove(); quietsTried++; continue
                }
                if (depth <= 3 && staticEval + 120 + 100 * depth <= alpha) {
                    b.unmakeMove(); quietsTried++; continue
                }
            }
            if (quiet) quietsTried++

            var score: Int
            if (legal == 1) {
                score = -negamax(depth - 1, -beta, -alpha, ply + 1, true)
            } else {
                var reduction = 0
                if (depth >= 3 && legal > 3 && quiet && !inCheck && !givesCheck) {
                    reduction = LMR[min(depth, 63)][min(legal, 63)]
                    if (pvNode) reduction--
                    if (m == killers[ply][0] || m == killers[ply][1]) reduction--
                    reduction = reduction.coerceIn(0, depth - 2)
                }
                score = -negamax(depth - 1 - reduction, -alpha - 1, -alpha, ply + 1, true)
                if (score > alpha && reduction > 0) {
                    score = -negamax(depth - 1, -alpha - 1, -alpha, ply + 1, true)
                }
                if (score > alpha && score < beta) {
                    score = -negamax(depth - 1, -beta, -alpha, ply + 1, true)
                }
            }
            b.unmakeMove()
            if (aborted) return 0

            if (score > best) {
                best = score
                bestMove = m
                if (score > alpha) {
                    alpha = score
                    // Update triangular PV.
                    val row = pvTable[ply]
                    row[ply] = m
                    val childLen = pvLen[ply + 1]
                    var j = ply + 1
                    while (j < childLen) {
                        row[j] = pvTable[ply + 1][j]
                        j++
                    }
                    pvLen[ply] = max(childLen, ply + 1)
                    if (score >= beta) {
                        if (quiet) {
                            if (killers[ply][0] != m) {
                                killers[ply][1] = killers[ply][0]
                                killers[ply][0] = m
                            }
                            val h = history[Move.from(m)]
                            h[Move.to(m)] = min(h[Move.to(m)] + depth * depth, 400_000)
                        }
                        break
                    }
                }
            }
        }

        if (legal == 0) {
            if (isRoot && rootExcluded.isNotEmpty()) return -INF
            return if (inCheck) -MATE + ply else 0
        }

        if (!(isRoot && rootExcluded.isNotEmpty())) {
            val flag = when {
                best >= beta -> TranspositionTable.LOWER
                best > alphaIn -> TranspositionTable.EXACT
                else -> TranspositionTable.UPPER
            }
            tt.store(key, bestMove, scoreToTt(best, ply), depth, flag)
        }
        return best
    }

    private fun quiesce(alphaIn: Int, beta: Int, ply: Int): Int {
        pvLen[ply] = ply
        if ((++nodes and 1023L) == 0L) checkLimits()
        if (aborted) return 0
        if (ply > selDepth) selDepth = ply
        if (ply >= MAX_PLY - 2) return Eval.evaluate(b)
        var alpha = alphaIn
        val inCheck = b.inCheck()
        var best: Int
        val standPat: Int
        if (inCheck) {
            best = -MATE + ply
            standPat = -INF
        } else {
            standPat = Eval.evaluate(b)
            if (standPat >= beta) return standPat
            if (standPat > alpha) alpha = standPat
            best = standPat
        }
        val moves = moveBuf[ply]
        val scores = scoreBuf[ply]
        val n = b.generatePseudo(moves, capturesOnly = !inCheck)
        scoreMoves(moves, scores, n, Move.NONE, ply)
        var legal = 0
        for (i in 0 until n) {
            pickNext(moves, scores, i, n)
            val m = moves[i]
            if (!inCheck && Move.promo(m) == 0) {
                val victim = if (Move.isEnPassant(m)) Piece.PAWN else Piece.type(b.pieceAt(Move.to(m)))
                if (standPat + Piece.VALUE[victim] + 200 < alpha) continue
            }
            if (!b.makeMove(m)) continue
            legal++
            val score = -quiesce(-beta, -alpha, ply + 1)
            b.unmakeMove()
            if (aborted) return 0
            if (score > best) {
                best = score
                if (score > alpha) {
                    alpha = score
                    if (score >= beta) break
                }
            }
        }
        if (inCheck && legal == 0) return -MATE + ply
        return best
    }

    private fun scoreMoves(moves: IntArray, scores: IntArray, n: Int, ttMove: Int, ply: Int) {
        val k0 = killers[ply][0]
        val k1 = killers[ply][1]
        for (i in 0 until n) {
            val m = moves[i]
            scores[i] = when {
                m == ttMove -> 10_000_000
                Move.isCapture(m) -> {
                    val victim = if (Move.isEnPassant(m)) Piece.PAWN else Piece.type(b.pieceAt(Move.to(m)))
                    val attacker = Piece.type(b.pieceAt(Move.from(m)))
                    1_000_000 + victim * 100 - attacker + (if (Move.promo(m) == Piece.QUEEN) 5000 else 0)
                }
                Move.promo(m) == Piece.QUEEN -> 950_000
                m == k0 -> 900_000
                m == k1 -> 800_000
                Move.promo(m) != 0 -> -1000
                else -> history[Move.from(m)][Move.to(m)]
            }
        }
    }

    private fun pickNext(moves: IntArray, scores: IntArray, i: Int, n: Int) {
        var bi = i
        var bs = scores[i]
        for (j in i + 1 until n) {
            if (scores[j] > bs) {
                bs = scores[j]
                bi = j
            }
        }
        if (bi != i) {
            val tm = moves[i]; moves[i] = moves[bi]; moves[bi] = tm
            val ts = scores[i]; scores[i] = scores[bi]; scores[bi] = ts
        }
    }

    companion object {
        const val MAX_PLY = 96
        const val INF = 32_500
        const val MATE = 32_000
        const val MATE_BOUND = 31_000

        private val LMR = Array(64) { d ->
            IntArray(64) { m ->
                if (d == 0 || m == 0) 0 else (0.75 + ln(d.toDouble()) * ln(m.toDouble()) / 2.25).toInt()
            }
        }

        fun mateIn(score: Int): Int? = when {
            score >= MATE_BOUND -> (MATE - score + 1) / 2
            score <= -MATE_BOUND -> -(MATE + score + 1) / 2
            else -> null
        }

        private fun scoreToTt(s: Int, ply: Int) = when {
            s >= MATE_BOUND -> s + ply
            s <= -MATE_BOUND -> s - ply
            else -> s
        }

        private fun scoreFromTt(s: Int, ply: Int) = when {
            s >= MATE_BOUND -> s - ply
            s <= -MATE_BOUND -> s + ply
            else -> s
        }
    }
}

/** Always-replace-if-deeper-or-older hash table packed into two long arrays. */
internal class TranspositionTable(sizeLog2: Int) {
    private val size = 1 shl sizeLog2
    private val mask = (size - 1).toLong()
    private val keys = LongArray(size)
    private val data = LongArray(size)
    private var age = 0

    fun clear() {
        keys.fill(0)
        data.fill(0)
    }

    fun newSearch() {
        age = (age + 1) and 63
    }

    fun probe(key: Long): Long {
        val i = (key and mask).toInt()
        return if (keys[i] == key) data[i] else 0L
    }

    fun store(key: Long, move: Int, score: Int, depth: Int, flag: Int) {
        val i = (key and mask).toInt()
        val old = data[i]
        if (keys[i] == key || old == 0L || ageOf(old) != age || depth >= depth(old)) {
            var mv = move
            if (mv == Move.NONE && keys[i] == key) mv = move(old)
            keys[i] = key
            data[i] = (mv.toLong() and 0xFFFFF) or
                ((score + 32768).toLong() and 0xFFFF shl 20) or
                ((depth.coerceIn(0, 255)).toLong() shl 36) or
                (flag.toLong() shl 44) or
                (age.toLong() shl 46) or
                (1L shl 52)
        }
    }

    private fun ageOf(e: Long) = ((e ushr 46) and 63).toInt()

    companion object {
        const val EXACT = 0
        const val LOWER = 1
        const val UPPER = 2

        fun move(e: Long) = (e and 0xFFFFF).toInt()
        fun score(e: Long) = ((e ushr 20) and 0xFFFF).toInt() - 32768
        fun depth(e: Long) = ((e ushr 36) and 255).toInt()
        fun flag(e: Long) = ((e ushr 44) and 3).toInt()
    }
}
