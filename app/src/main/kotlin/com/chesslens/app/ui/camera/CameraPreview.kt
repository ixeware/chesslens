package com.chesslens.app.ui.camera

import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chesslens.app.game.FrameMapper
import com.chesslens.app.game.GameViewModel
import com.chesslens.vision.CvUtils
import com.chesslens.vision.FrameBuffers
import java.util.concurrent.Executors

private const val ANALYSIS_MAX_SIDE = 640

/** Feeds camera frames to the vision pipeline; reports results in PreviewView coordinates. */
class BoardAnalyzer(private val vm: GameViewModel) : ImageAnalysis.Analyzer {
    @Volatile
    private var sensorToView: Matrix? = null

    private val buffers = FrameBuffers()

    override fun getTargetCoordinateSystem(): Int = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED

    override fun updateTransform(matrix: Matrix?) {
        sensorToView = matrix?.let { Matrix(it) }
    }

    override fun analyze(image: ImageProxy) {
        image.use {
            val pipeline = vm.pipeline ?: return
            val plane = image.planes[0]
            val rotation = image.imageInfo.rotationDegrees
            val gray = CvUtils.grayFromPlane(plane.buffer, image.width, image.height, plane.rowStride, rotation, ANALYSIS_MAX_SIDE, buffers)
            // Skin mask from the chroma planes: a hand resting over the board holds back move reads.
            val skin = if (image.planes.size >= 3) runCatching {
                val u = image.planes[1]
                val v = image.planes[2]
                CvUtils.skinFromChroma(u.buffer, v.buffer, image.width, image.height, u.rowStride, u.pixelStride, rotation, ANALYSIS_MAX_SIDE, buffers)
            }.getOrNull() else null
            try {
                val board = vm.visionBoard
                val frame = pipeline.process(gray, board, vm.autoOrientation, skin)
                // CameraX reports sensor -> view. Our coordinates are in the analysis buffer, so
                // go buffer -> sensor first (inverse of the image's sensor -> buffer transform).
                val mapper = sensorToView?.let { s2v ->
                    val bufferToView = Matrix()
                    image.imageInfo.sensorToBufferTransformMatrix.invert(bufferToView)
                    bufferToView.postConcat(s2v)
                    FrameMapper(image.width, image.height, rotation, FrameMapper.scaleFor(image.width, image.height, ANALYSIS_MAX_SIDE), bufferToView)
                }
                vm.onVisionFrame(frame, mapper, board)
            } finally {
                gray.release()
                skin?.release()
            }
        }
    }
}

@Composable
fun CameraPreview(vm: GameViewModel, torch: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val fourByThree = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.imageAnalysisResolutionSelector = fourByThree
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        controller.previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        controller.setImageAnalysisAnalyzer(executor, BoardAnalyzer(vm))
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }
    LaunchedEffect(torch) {
        runCatching { controller.enableTorch(torch) }
    }
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
        modifier = modifier,
    )
}
