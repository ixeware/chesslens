package com.chesslens.vision

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File

/**
 * Local debugging: turns frames of a ChessLens *screen recording* back into something like
 * camera frames by painting out the AR overlay (saturated neon colours) and the HUD pills, so
 * they can be fed to [VideoReplay]. Coordinates are for a 536x640 crop of a 1080-wide phone.
 * -Dchesslens.cleanIn=<raw frames> -Dchesslens.cleanOut=<out>
 */
class RecordingCleaner {
    init { nu.pattern.OpenCV.loadLocally() }

    @Test fun clean() {
        val inDir = System.getProperty("chesslens.cleanIn")
        val outDir = System.getProperty("chesslens.cleanOut")
        assumeTrue(inDir != null && outDir != null)
        File(outDir!!).mkdirs()
        val staticRects = listOf(
            Rect(14, 62, 74, 316), // left button column
            Rect(156, 62, 222, 72), // tracking pill
            Rect(446, 60, 80, 76), // engine ring
            Rect(266, 146, 258, 58), // engine chip
        )
        for (f in File(inDir!!).listFiles()!!.filter { it.extension == "png" }.sorted()) {
            val bgr = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_COLOR)
            val hsv = Mat()
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            // Saturated overlay colours (green, yellow, blue, purple, red); skin/wood hues kept.
            val sat = Mat()
            Core.inRange(hsv, Scalar(21.0, 80.0, 80.0), Scalar(179.0, 255.0, 255.0), sat)
            // Translucent neon green (arrows, rings) is only faintly saturated over grey squares.
            val green = Mat()
            Core.inRange(hsv, Scalar(36.0, 38.0, 60.0), Scalar(95.0, 255.0, 255.0), green)
            Core.bitwise_or(sat, green, sat)
            green.release()
            val red = Mat()
            Core.inRange(hsv, Scalar(0.0, 140.0, 120.0), Scalar(2.0, 255.0, 255.0), red)
            Core.bitwise_or(sat, red, sat)
            Imgproc.dilate(sat, sat, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0)))
            for (r in staticRects) Imgproc.rectangle(sat, r, Scalar(255.0), -1)
            // Banner / bottom pill: dark rounded bars that come and go.
            for (band in listOf(Rect(17, 222, 505, 52), Rect(140, 600, 270, 40))) {
                val roi = Mat(bgr, band)
                val gray = Mat()
                Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
                val dark = Mat()
                Imgproc.threshold(gray, dark, 40.0, 255.0, Imgproc.THRESH_BINARY_INV)
                if (Core.countNonZero(dark) > band.area() * 0.35) Imgproc.rectangle(sat, band, Scalar(255.0), -1)
                gray.release(); dark.release()
            }
            Imgproc.rectangle(sat, Point(0.0, 0.0), Point(535.0, 40.0), Scalar(255.0), -1) // status bar
            val out = Mat()
            Photo.inpaint(bgr, sat, out, 5.0, Photo.INPAINT_TELEA)
            Imgcodecs.imwrite("$outDir/${f.name}", out)
            bgr.release(); hsv.release(); sat.release(); red.release(); out.release()
        }
    }
}
