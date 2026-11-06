package com.chesslens.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {
    private fun perft(b: Board, depth: Int): Long {
        if (depth == 0) return 1
        val buf = IntArray(256)
        val n = b.generatePseudo(buf)
        var total = 0L
        for (i in 0 until n) {
            if (b.makeMove(buf[i])) {
                total += perft(b, depth - 1)
                b.unmakeMove()
            }
        }
        return total
    }

    @Test fun perftStart() {
        val b = Board.startPosition()
        assertEquals(20, perft(b, 1))
        assertEquals(400, perft(b, 2))
        assertEquals(8902, perft(b, 3))
        assertEquals(197281, perft(b, 4))
        assertEquals(Board.START_FEN, b.toFen())
    }

    @Test fun perftKiwipete() {
        val b = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, perft(b, 1))
        assertEquals(2039, perft(b, 2))
        assertEquals(97862, perft(b, 3))
    }

    @Test fun perftPosition3() {
        val b = Board.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14, perft(b, 1))
        assertEquals(191, perft(b, 2))
        assertEquals(2812, perft(b, 3))
        assertEquals(43238, perft(b, 4))
    }

    @Test fun perftPosition4() {
        val b = Board.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6, perft(b, 1))
        assertEquals(264, perft(b, 2))
        assertEquals(9467, perft(b, 3))
    }

    @Test fun perftPosition5() {
        val b = Board.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        assertEquals(44, perft(b, 1))
        assertEquals(1486, perft(b, 2))
        assertEquals(62379, perft(b, 3))
    }

    @Test fun zobristIsIncremental() {
        val b = Board.startPosition()
        for (uci in listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6", "e1e2")) {
            val m = b.parseUci(uci)
            assertNotNull(uci, m)
            b.makeMove(m!!)
            assertEquals(Board.fromFen(b.toFen()).key, b.key)
        }
    }

    @Test fun sanRoundTrip() {
        val b = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        for (m in b.legalMoves()) {
            val san = San.of(b, m)
            assertEquals(san, m, San.parse(b, san))
        }
        assertEquals("O-O", San.of(b, b.parseUci("e1g1")!!))
        assertEquals("Qxf6", San.of(b, b.parseUci("f3f6")!!))
    }

    @Test fun sanDisambiguationAndCheck() {
        val b = Board.fromFen("4k3/8/8/8/8/8/4K3/R6R w - - 0 1")
        assertEquals("Rad1", San.of(b, b.parseUci("a1d1")!!))
        assertEquals("Rhf1", San.of(b, b.parseUci("h1f1")!!))
        assertEquals("Ra8+", San.of(b, b.parseUci("a1a8")!!))
        val c = Board.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        assertEquals("Rf1", San.of(c, c.parseUci("h1f1")!!))
        val mate = Board.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        assertEquals("Ra8#", San.of(mate, mate.parseUci("a1a8")!!))
        assertEquals("Rook to A8, checkmate", San.spoken(mate, mate.parseUci("a1a8")!!))
    }

    @Test fun statusDetection() {
        val mated = Board.fromFen("R5k1/5ppp/8/8/8/8/8/6K1 b - - 0 1")
        assertEquals(GameStatus.CHECKMATE, mated.status())
        val stale = Board.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(GameStatus.STALEMATE, stale.status())
        val b = Board.startPosition()
        repeat(2) {
            for (uci in listOf("g1f3", "g8f6", "f3g1", "f6g8")) b.makeMove(b.parseUci(uci)!!)
        }
        assertTrue(b.isRepetition(2))
        assertEquals(GameStatus.THREEFOLD, b.status())
    }

    @Test fun epSquareOnlyWhenCapturable() {
        val b = Board.startPosition()
        b.makeMove(b.parseUci("e2e4")!!)
        assertEquals(-1, b.epSquare)
        val c = Board.fromFen("4k3/8/8/8/3p4/8/4P3/4K3 w - - 0 1")
        c.makeMove(c.parseUci("e2e4")!!)
        assertEquals(Square.parse("e3"), c.epSquare)
        assertFalse(c.legalMoves().none { Move.isEnPassant(it) })
    }
}
