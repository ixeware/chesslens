package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Move
import com.chesslens.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Mat

class VisionTest {
    init {
        nu.pattern.OpenCV.loadLocally()
    }

    private val tilted = BoardQuad(listOf(Pt(170f, 70f), Pt(470f, 80f), Pt(590f, 430f), Pt(50f, 420f)))
    private val frontal = BoardQuad(listOf(Pt(140f, 40f), Pt(500f, 40f), Pt(500f, 400f), Pt(140f, 400f)))

    @Test fun homographyRoundTrip() {
        val h = tilted.boardToImage
        for (p in BoardQuad.BOARD_CORNERS) {
            val img = h.map(p)
            val back = tilted.imageToBoard.map(img)
            assertEquals(p.x, back.x, 1e-3f)
            assertEquals(p.y, back.y, 1e-3f)
        }
        for (i in 0..3) {
            val m = h.map(BoardQuad.BOARD_CORNERS[i])
            assertEquals(tilted.corners[i].x, m.x, 1e-2f)
            assertEquals(tilted.corners[i].y, m.y, 1e-2f)
        }
        // Interior point must agree with OpenCV's perspective transform.
        val cv = org.opencv.imgproc.Imgproc.getPerspectiveTransform(
            CvUtils.toMat(BoardQuad.BOARD_CORNERS), CvUtils.toMat(tilted.corners),
        )
        val src = CvUtils.toMat(listOf(Pt(2f, 5f)))
        val dst = org.opencv.core.MatOfPoint2f()
        org.opencv.core.Core.perspectiveTransform(src, dst, cv)
        val p = dst.toArray()[0]
        val mine = h.map(2f, 5f)
        assertEquals(p.x.toFloat(), mine.x, 0.05f)
        assertEquals(p.y.toFloat(), mine.y, 0.05f)
    }

    @Test fun boardSpaceRoundTrip() {
        for (o in 0..3) for (sq in 0 until 64) {
            val c = BoardSpace.squareCenter(sq, o)
            assertEquals(sq, BoardSpace.squareAtCell(c.x.toInt(), c.y.toInt(), o))
        }
        // Orientation 0: a1 bottom-left, h8 top-right.
        assertEquals(Pt(0.5f, 7.5f), BoardSpace.squareCenter(0, 0))
        assertEquals(Pt(7.5f, 0.5f), BoardSpace.squareCenter(63, 0))
    }

    @Test fun detectsTiltedBoard() = checkDetection(tilted)

    @Test fun detectsFrontalBoard() = checkDetection(frontal)

    private fun checkDetection(outer: BoardQuad) {
        val img = SyntheticBoard.render(Board.startPosition(), outer)
        val r = BoardDetector().detect(img)
        assertNotNull("board not found", r)
        val truth = SyntheticBoard.gridOf(outer)
        val err = r!!.quad.maxCornerDistance(truth)
        println("detection error=${"%.2f".format(err)}px score=${"%.2f".format(r.score)} got=${r.quad.corners} truth=${truth.corners}")
        assertTrue("corner error $err", err < 7f)
    }

    @Test fun estimatesOrientation() {
        for (o in 0..3) {
            val img = SyntheticBoard.render(Board.startPosition(), frontal, orientation = o)
            val grid = SyntheticBoard.gridOf(frontal)
            val warped = CvUtils.bytes(CvUtils.warpBoard(img, grid, Cells.SIZE))
            assertEquals("orientation $o", o, OrientationEstimator.estimate(warped))
        }
    }

    @Test fun pipelineDetectsMovesThroughHandOcclusion() {
        val pipeline = VisionPipeline()
        val game = Board.startPosition()
        val seq = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5c6", "d7c6", "e1g1")
        var seed = 10
        fun feed(img: Mat): VisionFrame {
            val f = pipeline.process(img, game, autoOrientation = true)
            img.release()
            return f
        }
        // Lock onto the board.
        repeat(12) { feed(SyntheticBoard.render(game, tilted, orientation = 2, seed = seed++)) }
        assertEquals(TrackingPhase.LOCKED, pipeline.phase)
        assertEquals(2, pipeline.orientation)

        for (uci in seq) {
            val move = game.parseUci(uci)!!
            val after = game.copy().also { it.makeMove(move) }
            repeat(3) { feed(SyntheticBoard.render(game, tilted, orientation = 2, hand = true, seed = seed++)) }
            var detected: Int? = null
            repeat(10) {
                val f = feed(SyntheticBoard.render(after, tilted, orientation = 2, seed = seed++))
                val e = f.event
                if (e is VisionEvent.MoveDetected) detected = e.move
                if (e is VisionEvent.Unrecognized) println("unrecognized for $uci: ${e.squares}")
                f.squareChanges?.let { c ->
                    println("eval $uci phase=${f.phase} event=$e top=" + (0 until 64).sortedByDescending { c[it] }.take(5)
                        .joinToString { com.chesslens.chess.Square.name(it) + "=" + "%.1f".format(c[it]) })
                }
            }
            assertEquals("move $uci", uci, detected?.let { Move.uci(it) })
            game.makeMove(move)
        }
        pipeline.release()
    }

    @Test fun trackerFollowsCameraMotion() {
        // Render a larger scene once and move a 640x480 "camera" window across it.
        val sceneQuad = tilted.map { Pt(it.x + 60f, it.y + 40f) }
        val scene = SyntheticBoard.render(Board.startPosition(), sceneQuad, width = 780, height = 580, seed = 3)
        fun frame(dx: Int, dy: Int): Mat = scene.submat(dy, dy + 480, dx, dx + 640).clone()
        val tracker = BoardTracker()
        val grid0 = SyntheticBoard.gridOf(sceneQuad)
        tracker.reset(grid0.map { Pt(it.x - 60f, it.y - 40f) }, frame(60, 40))
        var last: BoardQuad? = null
        var dx = 60
        var dy = 40
        for (i in 1..10) {
            dx -= 5; dy -= 3
            val img = frame(dx, dy)
            last = tracker.update(img)
            img.release()
        }
        val truth = grid0.map { Pt(it.x - dx, it.y - dy) }
        val err = last!!.maxCornerDistance(truth)
        println("tracking error=${"%.2f".format(err)}px")
        assertTrue("tracking error $err", err < 2.5f)
    }

    @Test fun exposureChangeIsNotAMove() {
        val det = MoveDetector()
        val board = Board.startPosition()
        val grid = SyntheticBoard.gridOf(frontal)
        fun warped(brightness: Double, hand: Boolean = false): ByteArray {
            val img = SyntheticBoard.render(board, frontal, brightness = brightness, hand = hand, seed = 5)
            return CvUtils.bytes(CvUtils.warpBoard(img, grid, Cells.SIZE))
        }
        repeat(6) { det.process(warped(0.0), board, 0) }
        repeat(2) { det.process(warped(0.0, hand = true), board, 0) }
        val events = (0 until 8).mapNotNull { det.process(warped(18.0), board, 0).event }
        assertTrue("unexpected $events", events.none { it is VisionEvent.MoveDetected })
    }

    private fun playScreenGame(surface: BoardSurface, seq: List<String>, instantReplyAt: Int = -1): List<String> {
        val pipeline = VisionPipeline()
        pipeline.setSurface(surface)
        val game = Board.startPosition()
        var seed = 100
        var highlight: Pair<Int, Int>? = null
        val got = ArrayList<String>()
        fun feed(img: Mat): VisionFrame {
            val f = pipeline.process(img, game, autoOrientation = true)
            img.release()
            return f
        }
        repeat(10) { feed(SyntheticScreen.render(game, highlight, seed++, flicker = (seed % 3) * 2.0)) }
        assertEquals(TrackingPhase.LOCKED, pipeline.phase)
        var i = 0
        while (i < seq.size) {
            val pairUp = i == instantReplyAt
            val dbg = System.getProperty("chesslens.debugDir")
            var shotNo = 0
            if (dbg != null) pipeline.debugEval = { cur, ref ->
                val m = org.opencv.core.Mat(Cells.SIZE, Cells.SIZE * 2, org.opencv.core.CvType.CV_8UC1)
                val a = org.opencv.core.Mat(Cells.SIZE, Cells.SIZE, org.opencv.core.CvType.CV_8UC1); a.put(0, 0, ref)
                val b = org.opencv.core.Mat(Cells.SIZE, Cells.SIZE, org.opencv.core.CvType.CV_8UC1); b.put(0, 0, cur)
                a.copyTo(m.colRange(0, Cells.SIZE)); b.copyTo(m.colRange(Cells.SIZE, Cells.SIZE * 2))
                org.opencv.imgcodecs.Imgcodecs.imwrite("$dbg/screen_${surface}_${seq[i]}_${shotNo++}.png", m)
            }
            val moves = if (pairUp) listOf(seq[i], seq[i + 1]) else listOf(seq[i])
            val after = game.copy()
            val ms = moves.map { u -> after.parseUci(u)!!.also { after.makeMove(it) } }
            val last = ms.last()
            // One mid-animation frame, then the settled screen with the new highlight.
            feed(SyntheticScreen.render(after, highlight, seed++, slide = Triple(com.chesslens.chess.Move.from(last), com.chesslens.chess.Move.to(last), 0.5f)))
            highlight = com.chesslens.chess.Move.from(last) to com.chesslens.chess.Move.to(last)
            var detected: List<Int>? = null
            repeat(8) {
                val fr = feed(SyntheticScreen.render(after, highlight, seed++, flicker = (seed % 3) * 2.0))
                val e = fr.event
                if (e is VisionEvent.MoveDetected) detected = e.moves
            }
            val d = detected
            if (d == null) {
                got += "-"
                return got
            }
            for (m in d) {
                got += Move.uci(m)
                game.makeMove(m)
            }
            i += moves.size
        }
        pipeline.release()
        return got
    }

    @Test fun screenModeIgnoresMoveHighlights() {
        val seq = listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6")
        val got = playScreenGame(BoardSurface.SCREEN, seq)
        println("screen mode: $got")
        assertEquals(seq, got)
    }

    @Test fun screenModeReadsInstantReplies() {
        val seq = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5")
        // Black answers 2.Nf3 before the camera sees a still frame in between.
        val got = playScreenGame(BoardSurface.SCREEN, seq, instantReplyAt = 2)
        println("instant reply: $got")
        assertEquals(seq, got)
    }

    @Test fun structureChangeIsHighlightInvariant() {
        val b = Board.startPosition()
        val plain = CvUtils.bytes(CvUtils.warpBoard(SyntheticScreen.render(b, null, 7), SyntheticScreen.grid, Cells.SIZE))
        val lit = CvUtils.bytes(CvUtils.warpBoard(SyntheticScreen.render(b, Square.parse("e2") to Square.parse("g1"), 8), SyntheticScreen.grid, Cells.SIZE))
        val structural = Cells.structureChange(lit, plain, 0.78f)
        val raw = Cells.absDiff(lit, plain, 0.78f)
        println("highlight: structural max=${"%.1f".format(structural.max())} brightness max=${"%.1f".format(raw.max())}")
        assertTrue(structural.max() < 13.0)
    }

    @Test fun physicalModeOnScreenForComparison() {
        org.junit.Assume.assumeTrue(System.getProperty("chesslens.debugDir") != null)
        val seq = listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6")
        println("physical mode on screen: ${playScreenGame(BoardSurface.PHYSICAL, seq)}")
    }

    /** The X-corner locator finds a board that runs off the edge of the frame. */
    @Test fun gridLocatorHandlesBoardOffFrame() {
        val outer = tilted.map { Pt(it.x + 110f, it.y + 20f) }
        val img = SyntheticBoard.render(Board.startPosition(), outer, orientation = 2, seed = 4)
        val r = GridLocator().locate(img, expected = Board.startPosition().squares)
        assertNotNull("not found", r)
        val truth = SyntheticBoard.gridOf(outer)
        val err = r!!.quad.alignedTo(truth).maxCornerDistance(truth)
        println("off-frame grid error=${"%.2f".format(err)}px inliers=${r.inliers} orientation=${r.orientation}")
        assertTrue("corner error $err", err < 4f)
        assertEquals(2, r.orientation)
    }
}
