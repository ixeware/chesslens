package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Piece
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Local debugging: locks onto a recorded board and dumps per-square scan features at given frames.
 * -Dchesslens.replayDir=<frames> -Dchesslens.debugDir=<out> -Dchesslens.scanAt=30:<fen>,530:<fen>
 */
class ScanProbe {
    init { nu.pattern.OpenCV.loadLocally() }

    @Test fun probe() {
        val dir = System.getProperty("chesslens.replayDir")
        val out = System.getProperty("chesslens.debugDir")
        val at = System.getProperty("chesslens.scanAt")
        assumeTrue(dir != null && out != null && at != null)
        File(out!!).mkdirs()
        val targets = at!!.split(",").associate { val (f, fen) = it.split(":", limit = 2); f.toInt() to fen }
        val pipeline = VisionPipeline()
        val board = Board.startPosition()
        val frames = File(dir!!).listFiles()!!.filter { it.extension == "png" }.sorted()
        val scanner = BoardScanner()
        val sb = StringBuilder()
        for ((i, f) in frames.withIndex()) {
            if (i > targets.keys.max()) break
            val color = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_COLOR)
            val gray = Mat()
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
            val fr = pipeline.process(gray, board, autoOrientation = i < 40)
            val fen = targets[i]
            if (fen != null && fr.quad != null) {
                val truth = Board.fromFen(fen)
                val q = fr.quad!!
                val warped = CvUtils.warpBoard(gray, q, Cells.SIZE)
                val cam = BoardScanner.cameraDir(q, gray.rows())
                val scan = scanner.scan(CvUtils.bytes(warped), fr.orientation, cam, null, BoardScanner.steepness(q, cam))
                Imgcodecs.imwrite("$out/scan_$i.png", warped)
                sb.append("frame $i orientation=${fr.orientation}\n")
                var occErr = 0
                var colErr = 0
                for (rank in 7 downTo 0) {
                    for (file in 0..7) {
                        val sq = rank * 8 + file
                        val p = truth.squares[sq]
                        val o = scan.occupied(sq)
                        val tOcc = p != 0
                        val mark = when {
                            o != tOcc -> if (o) "+" else "-"
                            o && scan.white(sq) != (Piece.color(p) == Piece.WHITE) -> "c"
                            else -> " "
                        }
                        if (o != tOcc) occErr++
                        if (o && tOcc && scan.white(sq) != (Piece.color(p) == Piece.WHITE)) colErr++
                        val s = if (!o) "." else if (scan.white(sq)) "W" else "B"
                        sb.append("$s$mark")
                    }
                    sb.append("   ")
                    for (file in 0..7) sb.append("%5.1f".format(scan.occupancy()[rank * 8 + file]))
                    sb.append("   ")
                    for (file in 0..7) sb.append("%5.2f".format(scan.color()[rank * 8 + file]))
                    sb.append("\n")
                }
                sb.append("occupancy errors $occErr, colour errors $colErr\n${scan.note}\n\n")
            }
            gray.release(); color.release()
        }
        File("$out/scan.log").writeText(sb.toString())
        println(sb)
    }
}
