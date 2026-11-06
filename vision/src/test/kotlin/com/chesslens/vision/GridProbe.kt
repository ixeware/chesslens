package com.chesslens.vision

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class GridProbe {
    init { nu.pattern.OpenCV.loadLocally() }

    @Test fun probe() {
        val dir = System.getProperty("chesslens.replayDir")
        val out = System.getProperty("chesslens.debugDir")
        assumeTrue(dir != null && out != null)
        val frames = File(dir!!).listFiles()!!.filter { it.extension == "png" || it.extension == "jpg" }.sorted()
        val loc = GridLocator()
        val picks = if (frames.size > 100) listOf(0, 4, 40, 60, 100, 136, 200, 250, 300, 360, 420, 480, 528) else frames.indices.toList()
        var found = 0
        var total = 0L
        for (i in picks) {
            val g = Imgcodecs.imread(frames[i].absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            val t0 = System.nanoTime()
            val r = loc.locate(g)
            total += (System.nanoTime() - t0) / 1_000_000
            if (r != null) found++
            println("frame $i -> inliers=${r?.inliers} score=${r?.score?.let { "%.2f".format(it) }}")
            val bgr = Mat(); Imgproc.cvtColor(g, bgr, Imgproc.COLOR_GRAY2BGR)
            r?.let { res ->
                val q = res.quad
                for (k in 0..8) {
                    val a = q.boardToImage.map(k.toFloat(), 0f); val b = q.boardToImage.map(k.toFloat(), 8f)
                    Imgproc.line(bgr, Point(a.x.toDouble(), a.y.toDouble()), Point(b.x.toDouble(), b.y.toDouble()), Scalar(80.0, 230.0, 80.0), 1)
                    val c = q.boardToImage.map(0f, k.toFloat()); val d = q.boardToImage.map(8f, k.toFloat())
                    Imgproc.line(bgr, Point(c.x.toDouble(), c.y.toDouble()), Point(d.x.toDouble(), d.y.toDouble()), Scalar(80.0, 230.0, 80.0), 1)
                }
            }
            Imgcodecs.imwrite("$out/grid_${frames[i].nameWithoutExtension}.jpg", bgr)
        }
        println("found $found/${picks.size} avg ${total / picks.size} ms")
    }
}
