package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Move
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Replays a recorded game frame by frame through the real pipeline (local debugging only):
 * -Dchesslens.replayDir=<dir of f_0001.png…> -Dchesslens.debugDir=<out>
 */
class VideoReplay {
    init { nu.pattern.OpenCV.loadLocally() }

    @Test fun replay() {
        val dir = System.getProperty("chesslens.replayDir")
        val out = System.getProperty("chesslens.debugDir")
        assumeTrue(dir != null && out != null)
        File(out!!).mkdirs()
        val surface = System.getProperty("chesslens.surface")?.let { BoardSurface.valueOf(it) } ?: BoardSurface.PHYSICAL
        val pipeline = VisionPipeline().also { it.setSurface(surface) }
        val board = Board.startPosition()
        var frameNo = 0
        pipeline.debugEval = { cur, ref ->
            val both = Mat(Cells.SIZE, Cells.SIZE * 2, org.opencv.core.CvType.CV_8UC1)
            val a = Mat(Cells.SIZE, Cells.SIZE, org.opencv.core.CvType.CV_8UC1); a.put(0, 0, ref)
            val b = Mat(Cells.SIZE, Cells.SIZE, org.opencv.core.CvType.CV_8UC1); b.put(0, 0, cur)
            a.copyTo(both.colRange(0, Cells.SIZE)); b.copyTo(both.colRange(Cells.SIZE, Cells.SIZE * 2))
            Imgcodecs.imwrite("$out/eval_%04d.png".format(frameNo), both)
        }
        val frames = File(dir!!).listFiles()!!.filter { it.extension == "png" }.sorted()
        val log = StringBuilder()
        var lastPhase: TrackingPhase? = null
        var lastNote = ""
        var totalMs = 0L
        var maxMs = 0L
        for ((i, f) in frames.withIndex()) {
            if (System.getProperty("chesslens.stopAt")?.toInt()?.let { i > it } == true) break
            frameNo = i
            val gray = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            val fr = pipeline.process(gray, board, autoOrientation = board.plyCount == 0)
            totalMs += fr.processingMs
            maxMs = maxOf(maxMs, fr.processingMs)
            if (fr.processingMs > 150) log.append("frame $i: slow ${fr.processingMs}ms phase=${fr.phase} motion=${"%.1f".format(fr.motion)} hand=${fr.handOverBoard} ${pipeline.lastTimings}\n")
            if (fr.phase != lastPhase) log.append("frame $i: phase ${fr.phase} score=${"%.2f".format(fr.detectionScore)} orientation=${fr.orientation}\n")
            lastPhase = fr.phase
            when (val e = fr.event) {
                is VisionEvent.MoveDetected -> {
                    log.append("frame $i: MOVE ${e.moves.map { Move.uci(it) }} conf=${"%.2f".format(e.confidence)} top=${top(fr.squareChanges)} | ${pipeline.lastTimings.substringBefore(" lk=")}\n")
                    e.moves.forEach { board.makeMove(it) }
                }
                is VisionEvent.Unrecognized -> log.append("frame $i: UNRECOGNIZED ${e.squares.map { com.chesslens.chess.Square.name(it) }} top=${top(fr.squareChanges)} | ${pipeline.lastTimings.substringBefore(" lk=")}\n")
                is VisionEvent.Resynced -> log.append("frame $i: resynced\n")
                null -> {
                    val note = pipeline.lastTimings.substringBefore(" lk=")
                    if (fr.squareChanges != null || (note.startsWith("view moved") && note != lastNote)) {
                        log.append("frame $i: settled, no move. top=${top(fr.squareChanges)} | $note\n")
                    }
                    lastNote = note
                }
            }
            if (i % 6 == 0) {
                val bgr = Mat()
                Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR)
                fr.quad?.let { q ->
                    val col = if (fr.handOverBoard) Scalar(0.0, 200.0, 255.0) else Scalar(80.0, 230.0, 80.0)
                    Imgproc.polylines(bgr, listOf(MatOfPoint(*q.corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())), true, col, 2)
                    for (k in 1..7) {
                        val a = q.boardToImage.map(k.toFloat(), 0f); val b = q.boardToImage.map(k.toFloat(), 8f)
                        Imgproc.line(bgr, Point(a.x.toDouble(), a.y.toDouble()), Point(b.x.toDouble(), b.y.toDouble()), col, 1)
                        val c = q.boardToImage.map(0f, k.toFloat()); val d = q.boardToImage.map(8f, k.toFloat())
                        Imgproc.line(bgr, Point(c.x.toDouble(), c.y.toDouble()), Point(d.x.toDouble(), d.y.toDouble()), col, 1)
                    }
                    // a1 marker
                    val a1 = q.boardToImage.map(BoardSpace.squareCenter(0, fr.orientation))
                    Imgproc.circle(bgr, Point(a1.x.toDouble(), a1.y.toDouble()), 6, Scalar(0.0, 0.0, 255.0), -1)
                }
                Imgproc.putText(bgr, "$i ${fr.phase} m=${"%.1f".format(fr.motion)}", Point(6.0, 18.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, Scalar(0.0, 0.0, 255.0), 1)
                Imgcodecs.imwrite("$out/r_%04d.jpg".format(i), bgr)
            }
            gray.release()
        }
        log.append("final position: ${board.toFen()}\navg ms/frame: ${totalMs / maxOf(1, frames.size)} max: $maxMs\n")
        File("$out/replay.log").writeText(log.toString())
        println(log)
    }

    private fun top(c: DoubleArray?): String {
        c ?: return ""
        return (0 until 64).sortedByDescending { c[it] }.take(5).joinToString { com.chesslens.chess.Square.name(it) + "=" + "%.0f".format(c[it]) }
    }
}
