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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.chesslens.app.ui.components.BrandChip
import com.chesslens.app.ui.components.EngineChip
import com.chesslens.app.ui.components.GlassChip
import com.chesslens.app.ui.components.RoundIconButton
import com.chesslens.app.ui.components.TrackingChip
import com.chesslens.app.ui.components.VoicePill
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val viewport: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                preview()
                ArOverlay(vision, state, fx, Modifier.fillMaxSize())
                Hud(state, vision, speaking, torch, landscape, actions, onCalibrate = { calibrating = true })
                if (calibrating) {
                    CornerEditor(
                        initial = vision?.viewQuad,
                        onDone = { actions.onCorners(it); calibrating = false },
                        onCancel = { calibrating = false },
                    )
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                viewport(Modifier.weight(1f).fillMaxHeight())
                SidePanel(state, actions, onSettings = { showSettings = true }, onGame = { showMenu = true }, modifier = Modifier.width(290.dp).fillMaxHeight())
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
            ModalBottomSheet(onDismissRequest = { showMenu = false }, containerColor = Lens.Surface) {
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
    landscape: Boolean,
    actions: CameraActions,
    onCalibrate: () -> Unit,
) {
    val info = state.analysis?.takeIf { it.fen == state.position.fen }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandChip()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIconButton(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Torch", tint = if (torch) Lens.Yellow else Color.White, onClick = actions.onTorch)
                RoundIconButton(Icons.Rounded.CenterFocusStrong, "Set corners", onClick = onCalibrate)
                if (!landscape) RoundIconButton(Icons.Rounded.ScreenRotation, "Rotate board", onClick = actions.onRotate)
            }
        }
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TrackingChip(vision?.phase, vision?.fps ?: 0f)
            EngineChip(info?.depth ?: 0, info?.let { OverlayModel.evalText(it, it.best) } ?: "", info?.source ?: "Local", state.searching)
        }
        BannerToast(
            state.banner?.text, state.banner?.quality, state.banner?.error == true,
            Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 56.dp else 96.dp),
        )
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val hint = when {
                vision == null || vision.phase == TrackingPhase.SEARCHING -> "Point the camera at the board  ·  ⌖ to set corners"
                vision.phase == TrackingPhase.LOST -> "Hold steady — re-acquiring the board"
                vision.handOverBoard -> "Watching the move…"
                else -> null
            }
            if (hint != null && speaking == null) GlassChip { Text(hint, color = Color.White, fontSize = 13.sp) }
            VoicePill(speaking)
        }
    }
}

@Composable
private fun SidePanel(state: UiState, actions: CameraActions, onSettings: () -> Unit, onGame: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(Lens.GlassStrong)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MiniBoard(
                state, actions.onMove,
                Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
            )
            Column(Modifier.align(Alignment.TopEnd), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundIconButton(Icons.Rounded.PowerSettingsNew, "Exit", onClick = actions.onExit)
                RoundIconButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
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
            .background(Lens.GlassStrong)
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniBoard(state, actions.onMove, Modifier.size(128.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    RoundIconButton(Icons.Rounded.PowerSettingsNew, "Exit", onClick = actions.onExit)
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
                }
                MoveCard(state, compact = true)
            }
        }
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

private enum class MenuItem(val label: String, val icon: ImageVector) {
    NEW("New game", Icons.Rounded.RestartAlt),
    FEN("Set position (FEN)", Icons.Rounded.Edit),
    CORNERS("Adjust board corners", Icons.Rounded.CenterFocusStrong),
    RELOCK("Re-detect board", Icons.Rounded.Refresh),
    ROTATE("Rotate board 90°", Icons.Rounded.ScreenRotation),
    FLIP("Flip board", Icons.Rounded.SwapVert),
    RESYNC("Re-sync camera to app position", Icons.Rounded.Sync),
    ANALYSIS("Open analysis board", Icons.Rounded.Insights),
    PGN("Copy PGN", Icons.Rounded.ContentCopy),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
private fun GameMenu(onPick: (MenuItem) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
        Text("Game", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        for (item in MenuItem.entries) {
            Row(
                Modifier.fillMaxWidth().clickable { onPick(item) }.padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(item.icon, null, tint = Lens.Neon)
                Text(item.label, color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Lens.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
            Text("Camera access needed", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("ChessLens reads the board through your camera. Frames never leave the device.", color = Lens.TextSecondary, fontSize = 14.sp)
            Box(
                Modifier.background(Lens.Neon, androidx.compose.foundation.shape.CircleShape).clickable(onClick = onRequest).padding(horizontal = 20.dp, vertical = 10.dp),
            ) { Text("Allow camera", color = Lens.PillText, fontWeight = FontWeight.Bold) }
        }
    }
}
