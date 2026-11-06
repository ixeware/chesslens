package com.chesslens.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun best(fen: String, depth: Int = 8, multiPv: Int = 1): EngineInfo =
        Search(16).run(Board.fromFen(fen), SearchLimits(maxDepth = depth, timeMs = 20_000, multiPv = multiPv))

    @Test fun findsMateInOne() {
        val info = best("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", 4)
        assertEquals("a1a8", Move.uci(info.best!!.move))
        assertEquals(1, info.best!!.mateIn)
    }

    @Test fun findsMateInTwo() {
        // Classic queen sacrifice mate: 1.Qxh7+?? no — smothered style puzzle.
        val info = best("r1b2k1r/ppp1bppp/8/1B1Q4/5q2/2P5/PPP2PPP/R3R1K1 w - - 1 0", 6)
        assertEquals(2, info.best!!.mateIn)
        assertEquals("d5d8", Move.uci(info.best!!.move))
    }

    @Test fun winsHangingQueen() {
        val info = best("rnb1kbnr/pppp1ppp/8/4p1q1/4P3/3P4/PPP2PPP/RNBQKBNR w KQkq - 1 3", 6)
        assertEquals("c1g5", Move.uci(info.best!!.move))
        assertTrue(info.whiteCp() > 600)
    }

    @Test fun multiPvReturnsDistinctSortedLines() {
        val info = best(Board.START_FEN, 6, multiPv = 3)
        assertEquals(3, info.lines.size)
        assertEquals(3, info.lines.map { it.move }.toSet().size)
        assertTrue(info.lines[0].scoreCp >= info.lines[1].scoreCp)
        assertTrue(info.lines[1].scoreCp >= info.lines[2].scoreCp)
        assertTrue(info.best!!.moves.size >= 3)
    }

    @Test fun reasonableOpeningMove() {
        val info = best(Board.START_FEN, 7)
        val uci = Move.uci(info.best!!.move)
        assertTrue(uci, uci in setOf("e2e4", "d2d4", "g1f3", "c2c4", "b1c3", "e2e3", "d2d3"))
        println("start: depth=${info.depth} nodes=${info.nodes} ms=${info.timeMs} best=$uci cp=${info.whiteCp()}")
    }

    @Test fun benchmarkMiddlegame() {
        val info = Search(18).run(
            Board.fromFen("r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP3PPP/R2QKB1R w KQ - 0 8"),
            SearchLimits(timeMs = 3000),
        )
        println("middlegame: depth=${info.depth} nodes=${info.nodes} ms=${info.timeMs} nps=${info.nodes * 1000 / maxOf(1, info.timeMs)} pv=${info.best!!.moves.joinToString(" ") { Move.uci(it) }}")
        assertTrue(info.depth >= 6)
    }

    @Test fun classifierFlagsBlunder() {
        val before = Board.fromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2")
        val s = Search(16)
        val infoBefore = s.run(before, SearchLimits(maxDepth = 7, multiPv = 2))
        val blunder = before.parseUci("d8g5")!!
        val after = before.copy().also { it.makeMove(blunder) }
        val infoAfter = s.run(after, SearchLimits(maxDepth = 7))
        assertEquals(MoveQuality.BLUNDER, MoveClassifier.classify(infoBefore, infoAfter, before, blunder, 3))
    }

    @Test fun seeAndHanging() {
        val b = Board.fromFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1")
        assertEquals(100, Tactics.see(b, b.parseUci("e4d5")!!))
        val c = Board.fromFen("4k3/8/2p5/3p4/4P3/8/8/4K3 w - - 0 1")
        assertEquals(0, Tactics.see(c, c.parseUci("e4d5")!!))
        val d = Board.fromFen("4k3/8/2n5/8/3Q4/8/8/4K3 b - - 0 1")
        assertEquals(listOf(Square.parse("d4")), Tactics.hangingPieces(d, Piece.WHITE))
    }
}
