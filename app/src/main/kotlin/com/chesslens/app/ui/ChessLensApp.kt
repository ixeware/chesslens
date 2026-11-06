package com.chesslens.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chesslens.app.game.GameViewModel
import com.chesslens.app.ui.analysis.AnalysisScreen
import com.chesslens.app.ui.camera.CameraScreen
import com.chesslens.app.ui.home.HomeScreen

enum class Screen { HOME, CAMERA, ANALYSIS }

@Composable
fun ChessLensApp() {
    val vm: GameViewModel = viewModel()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { s ->
        when (s) {
            Screen.HOME -> HomeScreen(vm, onStartCamera = { screen = Screen.CAMERA }, onAnalysis = { screen = Screen.ANALYSIS })
            Screen.CAMERA -> CameraScreen(vm, onExit = { screen = Screen.HOME }, onOpenAnalysis = { screen = Screen.ANALYSIS })
            Screen.ANALYSIS -> AnalysisScreen(vm, onBack = { screen = Screen.HOME }, onCamera = { screen = Screen.CAMERA })
        }
    }
}
