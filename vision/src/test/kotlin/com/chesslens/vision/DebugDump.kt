package com.chesslens.vision

import com.chesslens.chess.Board
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** Writes annotated detection images when -Dchesslens.debugDir is set (local debugging only). */
class DebugDump {
    init { nu.pattern.OpenCV.loadLocally() }

    private val outDir = System.getProperty("chesslens.debugDir")

    private fun draw(img: Mat, q: BoardQuad?, color: Scalar) {
        q ?: return
        val pts = q.corners.map { Point(it.x.toDouble(), it.y.toDouble()) }
        Imgproc.polylines(img, listOf(MatOfPoint(*pts.toTypedArray())), true, color, 2)
        for (i in 1..7) {
            val a = q.boardToImage.map(i.toFloat(), 0f); val b = q.boardToImage.map(i.toFloat(), 8f)
            Imgproc.line(img, Point(a.x.toDouble(), a.y.toDouble()), Point(b.x.toDouble(), b.y.toDouble()), color, 1)
            val c = q.boardToImage.map(0f, i.toFloat()); val d = q.boardToImage.map(8f, i.toFloat())
            Imgproc.line(img, Point(c.x.toDouble(), c.y.toDouble()), Point(d.x.toDouble(), d.y.toDouble()), color, 1)
        }
    }

    @Test fun dumpSynthetic() {
        assumeTrue(outDir != null)
        File(outDir!!).mkdirs()
        val quads = mapOf(
            "tilted" to BoardQuad(listOf(Pt(170f, 70f), Pt(470f, 80f), Pt(590f, 430f), Pt(50f, 420f))),
            "frontal" to BoardQuad(listOf(Pt(140f, 40f), Pt(500f, 40f), Pt(500f, 400f), Pt(140f, 400f))),
        )
        for ((name, q) in quads) {
            val img = SyntheticBoard.render(Board.startPosition(), q)
            val r = BoardDetector().detect(img)
            val color = Mat(); Imgproc.cvtColor(img, color, Imgproc.COLOR_GRAY2BGR)
            draw(color, SyntheticBoard.gridOf(q), Scalar(0.0, 255.0, 0.0))
            draw(color, r?.quad, Scalar(0.0, 0.0, 255.0))
            Imgcodecs.imwrite("$outDir/synthetic_$name.png", color)
            Imgcodecs.imwrite("$outDir/warp_truth_$name.png", CvUtils.warpBoard(img, SyntheticBoard.gridOf(q), 256))
            r?.let { Imgcodecs.imwrite("$outDir/warp_det_$name.png", CvUtils.warpBoard(img, it.quad, 256)) }
        }
    }

    @Test fun dumpRealFrames() {
        val dir = System.getProperty("chesslens.framesDir")
        assumeTrue(outDir != null && dir != null)
        File(outDir!!).mkdirs()
        for (f in File(dir!!).listFiles()!!.filter { it.extension == "jpg" }.sorted()) {
            val bgr = Imgcodecs.imread(f.absolutePath)
            val gray = Mat(); Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val t0 = System.currentTimeMillis()
            val r = BoardDetector().detect(gray)
            val ms = System.currentTimeMillis() - t0
            println("${f.name}: score=${r?.score} ms=$ms")
            draw(bgr, r?.quad, Scalar(0.0, 0.0, 255.0))
            if (r != null) {
                val warped = CvUtils.bytes(CvUtils.warpBoard(gray, r.quad, Cells.SIZE))
                println("  orientation=${OrientationEstimator.estimate(warped)}")
            }
            Imgcodecs.imwrite("$outDir/${f.nameWithoutExtension}_det.png", bgr)
        }
    }
}
