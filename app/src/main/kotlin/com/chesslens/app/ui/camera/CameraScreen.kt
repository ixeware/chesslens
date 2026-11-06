package com.chesslens.app.ui.camera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chesslens.app.ChessLensApplication
import com.chesslens.app.game.GameViewModel
import com.chesslens.app.game.Settings
import com.chesslens.app.game.UiEvent
import com.chesslens.app.game.UiState
import com.chesslens.app.game.VisionUi
import com.chesslens.app.ui.ar.ArOverlay
import com.chesslens.app.ui.ar.BadgeFx
import com.chesslens.app.ui.ar.OverlayFx
import com.chesslens.app.ui.ar.OverlayModel
import com.chesslens.app.ui.components.BannerToast
import com.chesslens.app.ui.components.BevelButton
import com.chesslens.app.ui.components.BevelIconButton
import com.chesslens.app.ui.components.EngineChip
import com.chesslens.app.ui.components.EngineRing
import com.chesslens.app.ui.components.GlowButton
import com.chesslens.app.ui.components.HudPill
import com.chesslens.app.ui.components.IconTile
import com.chesslens.app.ui.components.TopPill
import com.chesslens.app.ui.components.TrackingStatus
import com.chesslens.app.ui.components.VoicePill
import com.chesslens.app.ui.components.forgeSlab
import com.chesslens.vision.BoardSurface
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Move
import com.chesslens.vision.TrackingPhase
import kotlinx.coroutines.delay

/** All user actions available on the camera screen (keeps the content composable stateless). */
data class CameraActions(
    val onExit: () -> Unit = {},
    val onTorch: () -> Unit = {},
    val onSettings: ((Settings) -> Settings) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onMove: (Int, Int) -> Unit = { _, _ -> },
    val onNewGame: () -> Unit = {},
    val onSetFen: (String) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onFlip: () -> Unit = {},
    val onRelock: () -> Unit = {},
    val onResync: () -> Unit = {},
    val onCorners: (List<Offset>) -> Unit = {},
    val onOpenAnalysis: () -> Unit = {},
    val onCopyPgn: () -> Unit = {},
)

@Composable
fun CameraScreen(vm: GameViewModel, onExit: () -> Unit, onOpenAnalysis: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val vision by vm.vision.collectAsStateWithLifecycle()
    val speaking by vm.speech.speaking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) {
        vm.setDeepAnalysis(false)
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }
    KeepScreenOn()
    var torch by remember { mutableStateOf(false) }
    val fx = rememberOverlayFx(vm)
    LaunchedEffect(state.banner?.id) {
        if (state.banner != null) {
            delay(4000)
            vm.dismissBanner()
        }
    }
    LaunchedEffect(speaking) {
        if (speaking != null) {
            delay(6000)
            vm.speech.clearCaption()
        }
    }
    val actions = CameraActions(
        onExit = onExit,
        onTorch = { torch = !torch },
        onSettings = vm::updateSettings,
        onUndo = vm::undo,
        onMove = vm::playFromTo,
        onNewGame = { vm.newGame() },
        onSetFen = { vm.newGame(it) },
        onRotate = vm::rotateBoard,
        onFlip = vm::flipBoard,
        onRelock = vm::relock,
        onResync = vm::resync,
        onCorners = vm::setManualCorners,
        onOpenAnalysis = onOpenAnalysis,
        onCopyPgn = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PGN", vm.pgn()))
            Toast.makeText(context, "PGN copied", Toast.LENGTH_SHORT).show()
        },
    )
    CameraScreenContent(state, vision, speaking, fx, torch, actions) {
        when {
            !hasPermission -> PermissionPrompt { launcher.launch(Manifest.permission.CAMERA) }
            !ChessLensApplication.openCvReady -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("Computer vision failed to load on this device", color = Color.White)
            }
            else -> CameraPreview(vm, torch, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun rememberOverlayFx(vm: GameViewModel): OverlayFx {
    var fx by remember { mutableStateOf(OverlayFx()) }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            val now = System.nanoTime()
            fx = when (e) {
                UiEvent.BoardLocked -> fx.copy(lockNanos = now)
                is UiEvent.MovePlayed -> if (e.fromCamera) fx.copy(waveNanos = now, waveOrigin = Move.to(e.move), badge = null) else fx.copy(badge = null)
                is UiEvent.Classified -> if (e.ply == vm.state.value.moves.size - 1) fx.copy(badge = BadgeFx(Move.to(e.move), e.quality, now)) else fx
                is UiEvent.Unrecognized -> fx
            }
        }
    }
    return fx
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreenContent(
    state: UiState,
    vision: VisionUi?,
    speaking: String?,
    fx: OverlayFx,
    torch: Boolean,
    actions: CameraActions,
    preview: @Composable () -> Unit,
) {
    var calibrating by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Lens.Ground)) {
        val landscape = maxWidth > maxHeight
        val viewport: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                preview()
                ArOverlay(vision, state, fx, Modifier.fillMaxSize())
                if (calibrating) {
                    CornerEditor(
                        initial = vision?.viewQuad,
                        onDone = { actions.onCorners(it); calibrating = false },
                        onCancel = { calibrating = false },
                    )
                } else {
                    Hud(state, vision, speaking, torch, actions, onCalibrate = { calibrating = true })
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                viewport(Modifier.weight(1f).fillMaxHeight())
                SidePanel(state, actions, onSettings = { showSettings = true }, onGame = { showMenu = true }, modifier = Modifier.width(300.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                viewport(Modifier.weight(1f).fillMaxWidth())
                BottomPanel(state, actions, onSettings = { showSettings = true }, onGame = { showMenu = true })
            }
        }
        if (showSettings) SettingsSheet(state.settings, actions.onSettings) { showSettings = false }
        if (showFen) FenDialog(onDismiss = { showFen = false }, onLoad = { actions.onSetFen(it); showFen = false })
        if (showMenu) {
            ModalBottomSheet(onDismissRequest = { showMenu = false }, containerColor = Lens.Slab) {
                GameMenu(
                    onPick = { item ->
                        showMenu = false
                        when (item) {
                            MenuItem.NEW -> actions.onNewGame()
                            MenuItem.FEN -> showFen = true
                            MenuItem.CORNERS -> calibrating = true
                            MenuItem.RELOCK -> actions.onRelock()
                            MenuItem.ROTATE -> actions.onRotate()
                            MenuItem.FLIP -> actions.onFlip()
                            MenuItem.RESYNC -> actions.onResync()
                            MenuItem.ANALYSIS -> actions.onOpenAnalysis()
                            MenuItem.PGN -> actions.onCopyPgn()
                            MenuItem.SETTINGS -> showSettings = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Hud(
    state: UiState,
    vision: VisionUi?,
    speaking: String?,
    torch: Boolean,
    actions: CameraActions,
    onCalibrate: () -> Unit,
) {
    val info = state.analysis?.takeIf { it.fen == state.position.fen }
    val screen = state.settings.surface == BoardSurface.SCREEN
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        // Top bar: back · tracker status · engine ring.
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
            BevelIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to home", onClick = actions.onExit)
            Spacer(Modifier.weight(1f))
            TopPill { TrackingStatus(vision?.phase, vision?.fps ?: 0f, screen) }
            Spacer(Modifier.weight(1f))
            EngineRing(((info?.depth ?: 0) / 24f), description = "Engine depth ${info?.depth ?: 0}")
        }
        Column(Modifier.align(Alignment.TopStart).padding(top = 60.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BevelIconButton(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, if (torch) "Torch off" else "Torch on", selected = torch, onClick = actions.onTorch)
            BevelIconButton(Icons.Rounded.CenterFocusStrong, "Set board corners", onClick = onCalibrate)
            BevelIconButton(Icons.Rounded.ScreenRotation, "Rotate board", onClick = actions.onRotate)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 60.dp), horizontalAlignment = Alignment.End) {
            EngineChip(info?.depth ?: 0, info?.let { OverlayModel.evalText(it, it.best) } ?: "", info?.source ?: "Local", state.searching)
        }
        BannerToast(
            state.banner?.text, state.banner?.quality, state.banner?.error == true,
            Modifier.align(Alignment.TopCenter).padding(top = 112.dp),
        )
        // 3D board / 2D screen quick toggle.
        BevelButton(
            onClick = { actions.onSettings { it.copy(surface = if (screen) BoardSurface.PHYSICAL else BoardSurface.SCREEN) } },
            modifier = Modifier.align(Alignment.BottomStart),
            description = if (screen) "Looking at a screen. Switch to a real board" else "Looking at a real board. Switch to a screen",
        ) {
            Icon(if (screen) Icons.Rounded.DesktopWindows else Icons.Rounded.ViewInAr, null, tint = Lens.Green, modifier = Modifier.size(20.dp))
            Text(if (screen) "2D screen" else "3D board", style = MaterialTheme.typography.labelMedium, color = Lens.Text)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hint = when {
                vision == null || vision.phase == TrackingPhase.SEARCHING -> if (screen) "Fit the whole screen board in view" else "Fit the whole board in view"
                vision.phase == TrackingPhase.LOST -> "Hold steady, re-acquiring"
                vision.handOverBoard -> "Watching the move"
                else -> null
            }
            if (hint != null && speaking == null) {
                HudPill {
                    Icon(if (vision?.handOverBoard == true) Icons.Rounded.PanTool else Icons.Rounded.CenterFocusWeak, null, tint = Lens.TextDim, modifier = Modifier.size(18.dp))
                    Text(hint, style = MaterialTheme.typography.titleSmall, color = Lens.Text)
                }
            }
            VoicePill(speaking)
        }
    }
}

@Composable
private fun SidePanel(state: UiState, actions: CameraActions, onSettings: () -> Unit, onGame: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(Lens.Slab)
            .drawBehind { drawLine(Lens.Line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniBoard(state, actions.onMove, Modifier.weight(1f).fillMaxHeight().wrapContentWidth().aspectRatio(1f, matchHeightConstraintsFirst = true))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BevelIconButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
                BevelIconButton(Icons.Rounded.Insights, "Analysis board", onClick = actions.onOpenAnalysis)
            }
        }
        MoveCard(state, compact = true)
        PanelButtons(
            state,
            onToggleAr = { actions.onSettings { it.copy(arEnabled = !it.arEnabled) } },
            onToggleVoice = { actions.onSettings { it.copy(voice = !it.voice) } },
            onUndo = actions.onUndo,
            onGame = onGame,
        )
        ModeSwitch(state.settings.overlayMode, { m -> actions.onSettings { it.copy(overlayMode = m) } })
    }
}

@Composable
private fun BottomPanel(state: UiState, actions: CameraActions, onSettings: () -> Unit, onGame: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Lens.Slab)
            .drawBehind { drawLine(Lens.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniBoard(state, actions.onMove, Modifier.size(124.dp))
            }
            MoveCard(state, Modifier.weight(1f), compact = true)
        }
        PanelButtons(
            state,
            onToggleAr = { actions.onSettings { it.copy(arEnabled = !it.arEnabled) } },
            onToggleVoice = { actions.onSettings { it.copy(voice = !it.voice) } },
            onUndo = actions.onUndo,
            onGame = onGame,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeSwitch(state.settings.overlayMode, { m -> actions.onSettings { it.copy(overlayMode = m) } }, Modifier.weight(1f))
            BevelIconButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
        }
    }
}

private enum class MenuItem(val label: String, val icon: ImageVector) {
    NEW("New game", Icons.Rounded.RestartAlt),
    FEN("Set position from FEN", Icons.Rounded.Edit),
    CORNERS("Adjust board corners", Icons.Rounded.CenterFocusStrong),
    RELOCK("Find the board again", Icons.Rounded.Refresh),
    ROTATE("Rotate board 90°", Icons.Rounded.ScreenRotation),
    FLIP("Flip board", Icons.Rounded.SwapVert),
    RESYNC("Match camera to app position", Icons.Rounded.Sync),
    ANALYSIS("Open analysis board", Icons.Rounded.Insights),
    PGN("Copy PGN", Icons.Rounded.ContentCopy),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
private fun GameMenu(onPick: (MenuItem) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text("Game", style = MaterialTheme.typography.headlineMedium, color = Lens.Text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        for (item in MenuItem.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPick(item) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconTile(item.icon, Color(0xFF3A3A3A), Color(0xFF161616), size = 40.dp)
                Text(item.label, style = MaterialTheme.typography.titleMedium, color = Lens.Text)
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Lens.Ground).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.forgeSlab().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(Icons.Rounded.PhotoCamera, Lens.Green, Lens.GreenDeep, size = 56.dp)
            Text("Camera access", style = MaterialTheme.typography.headlineMedium, color = Lens.Text)
            Text(
                "ChessLens reads the board through the camera. Frames are processed on the phone and never uploaded.",
                style = MaterialTheme.typography.bodyLarge, color = Lens.TextDim, textAlign = TextAlign.Center,
            )
            GlowButton("Allow camera", Icons.Rounded.PhotoCamera, onRequest, Modifier.fillMaxWidth())
        }
    }
}
