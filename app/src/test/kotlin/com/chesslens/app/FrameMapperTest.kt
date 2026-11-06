package com.chesslens.app

import android.graphics.Matrix
import com.chesslens.app.game.FrameMapper
import com.chesslens.vision.Pt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The AR layer must land on the board: CameraX gives a sensor -> view matrix, frames come in
 * analysis-buffer pixels. Models a phone back camera (4032x3024 sensor, 1280x960 analysis,
 * 90° rotation) shown full-screen in portrait.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FrameMapperTest {
    @Test fun uprightAnalysisPointsLandOnTheSameSpotInThePreview() {
        val sensorW = 4032f
        val sensorH = 3024f
        val viewW = 1080f
        val viewH = 1440f
        val k = viewH / sensorW
        val sensorToView = Matrix().apply {
            postRotate(90f)
            postTranslate(sensorH, 0f)
            postScale(k, k)
        }
        val sensorToBuffer = Matrix().apply { setScale(1280f / sensorW, 960f / sensorH) }
        val bufferToView = Matrix().also { sensorToBuffer.invert(it) }.apply { postConcat(sensorToView) }
        val scale = FrameMapper.scaleFor(1280, 960, 640)
        val mapper = FrameMapper(1280, 960, 90, scale, bufferToView)

        // Upright analysis image is 480x640 (portrait).
        val center = mapper.toView(Pt(240f, 320f))
        assertEquals(viewW / 2, center.x, 1f)
        assertEquals(viewH / 2, center.y, 1f)
        val topLeft = mapper.toView(Pt(0f, 0f))
        assertEquals(0f, topLeft.x, 1f)
        assertEquals(0f, topLeft.y, 1f)
        val bottomRight = mapper.toView(Pt(480f, 640f))
        assertEquals(viewW, bottomRight.x, 1f)
        assertEquals(viewH, bottomRight.y, 1f)
        // And back (manual corner placement).
        val back = mapper.fromView(androidx.compose.ui.geometry.Offset(300f, 900f))
        val again = mapper.toView(back)
        assertEquals(300f, again.x, 0.5f)
        assertEquals(900f, again.y, 0.5f)
    }
}
