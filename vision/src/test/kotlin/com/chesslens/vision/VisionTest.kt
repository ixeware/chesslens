package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Move
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
}
