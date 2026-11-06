package com.chesslens.app.game

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chesslens.app.ChessLensApplication
import com.chesslens.chess.Board
import com.chesslens.chess.EngineInfo
import com.chesslens.chess.EngineLine
import com.chesslens.chess.Eval
import com.chesslens.chess.Game
import com.chesslens.chess.GameStatus
import com.chesslens.chess.MoveClassifier
import com.chesslens.chess.MoveQuality
import com.chesslens.chess.Piece
import com.chesslens.chess.PlayedMove
import com.chesslens.chess.San
import com.chesslens.chess.SearchLimits
import com.chesslens.chess.Tactics
import com.chesslens.vision.BoardQuad
import com.chesslens.vision.TrackingPhase
import com.chesslens.vision.VisionEvent
import com.chesslens.vision.VisionFrame
import com.chesslens.vision.VisionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.exp

/** Immutable view of one position for the UI. */
data class PositionSnapshot(
    val fen: String,
    val squares: IntArray,
    val side: Int,
    val inCheck: Boolean,
    val kingSquare: Int,
    val lastMove: Int,
    val lastSan: String?,
    val status: GameStatus,
    val moveNumber: Int,
    val legalMoves: List<Int>,
    val hanging: List<Int>,
    val control: IntArray,
) {
    companion object {
        fun of(board: Board, lastSan: String?): PositionSnapshot = PositionSnapshot(
            fen = board.toFen(),
            squares = board.squares.copyOf(),
            side = board.side,
            inCheck = board.inCheck(),
            kingSquare = board.kingSquare(board.side),
            lastMove = board.lastMove(),
            lastSan = lastSan,
            status = board.status(),
            moveNumber = board.fullmoveNumber,
            legalMoves = board.legalMoves(),
            hanging = Tactics.hangingPieces(board, Piece.WHITE) + Tactics.hangingPieces(board, Piece.BLACK),
            control = Tactics.controlMap(board),
        )
    }
}

data class Banner(val text: String, val quality: MoveQuality? = null, val error: Boolean = false, val id: Long = System.nanoTime())

data class UiState(
    val moves: List<PlayedMove> = emptyList(),
    val viewPly: Int = 0,
    val position: PositionSnapshot = PositionSnapshot.of(Board.startPosition(), null),
    val analysis: EngineInfo? = null,
    val searching: Boolean = false,
    val threat: EngineLine? = null,
    val qualities: Map<Int, MoveQuality> = emptyMap(),
    /** White-POV centipawns of the position after ply i (index 0 = start), null if unknown. */
    val evalByPly: List<Int?> = listOf(null),
    val accuracyWhite: Double? = null,
    val accuracyBlack: Double? = null,
    val settings: Settings = Settings(),
    val banner: Banner? = null,
    val startFen: String = Board.START_FEN,
) {
    val isLive: Boolean get() = viewPly == moves.size
}

/** Vision state in view coordinates, updated at camera rate. */
data class VisionUi(
    val viewQuad: List<Offset>?,
    val phase: TrackingPhase,
    val orientation: Int,
    val handOverBoard: Boolean,
    val fps: Float,
    val processingMs: Long,
    val mapper: FrameMapper?,
    val changedSquares: List<Int> = emptyList(),
)

sealed interface UiEvent {
    data class MovePlayed(val ply: Int, val move: Int, val san: String, val fromCamera: Boolean) : UiEvent
    data class Classified(val ply: Int, val move: Int, val quality: MoveQuality) : UiEvent
    data class Unrecognized(val squares: List<Int>) : UiEvent
    data object BoardLocked : UiEvent
}

class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val engine = EngineService(20, "engine")
    private val threatEngine = EngineService(16, "threat")
    val speech = Speech(app)

    private var game = Game()
    private val evalCache = HashMap<String, EngineInfo>()
    private val qualities = HashMap<Int, MoveQuality>()
    private val winLoss = HashMap<Int, Double>()
    private val spokenFens = HashSet<String>()
    private var cloudJob: Job? = null
    private var analysisDeep = false

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state

    private val _vision = MutableStateFlow<VisionUi?>(null)
    val vision: StateFlow<VisionUi?> = _vision

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events

    /** Vision pipeline (thread-confined to the camera analyzer except for its request methods). */
    val pipeline: VisionPipeline? by lazy {
        if (ChessLensApplication.openCvReady) VisionPipeline().also { it.setSurface(_state.value.settings.surface) } else null
    }

    /** Position the physical board is believed to show; read by the analyzer thread. */
    @Volatile
    var visionBoard: Board = game.current.copy()
        private set

    @Volatile
    var autoOrientation: Boolean = true
        private set

    private var lastFrameNs = 0L
    private var fps = 0f
    private var lastPhase: TrackingPhase? = null

    init {
        refresh()
        analyzeCurrent()
    }

    // ---------------------------------------------------------------- game actions

    fun playMove(move: Int, fromCamera: Boolean = false) {
        if (!_state.value.isLive) {
            // Playing from a past position in analysis branches off: truncate the game there.
            truncateTo(_state.value.viewPly)
        }
        val boardBefore = game.current.copy()
        val pm = game.play(move) ?: return
        val ply = game.moves.size - 1
        visionBoard = game.current.copy()
        if (!fromCamera) pipeline?.requestResync(afterMotion = false)
        refresh(viewPly = game.moves.size)
        _events.tryEmit(UiEvent.MovePlayed(ply, move, pm.san, fromCamera))
        if (game.current.inCheck() && _state.value.settings.voice) {
            val st = game.current.status()
            if (st == GameStatus.CHECKMATE) speech.say("Checkmate")
        }
        tryClassify(ply, boardBefore)
        analyzeCurrent()
    }

    fun playFromTo(from: Int, to: Int) {
        val board = boardAtView()
        val m = board.findMove(from, to) ?: return
        if (!_state.value.isLive) truncateTo(_state.value.viewPly)
        playMove(m)
    }

    fun undo() {
        if (game.moves.isEmpty()) return
        game.undo()
        qualities.remove(game.moves.size)
        winLoss.remove(game.moves.size)
        visionBoard = game.current.copy()
        pipeline?.requestResync(afterMotion = true)
        refresh(viewPly = game.moves.size)
        analyzeCurrent()
    }

    fun newGame(fen: String = Board.START_FEN) {
        val g = runCatching { Game(fen) }.getOrNull() ?: run {
            banner("Invalid FEN", error = true)
            return
        }
        game = g
        qualities.clear()
        winLoss.clear()
        spokenFens.clear()
        visionBoard = game.current.copy()
        pipeline?.requestResync(afterMotion = false)
        refresh(viewPly = 0)
        analyzeCurrent()
    }

    fun goToPly(ply: Int) {
        val p = ply.coerceIn(0, game.moves.size)
        if (p == _state.value.viewPly) return
        refresh(viewPly = p)
        analyzeCurrent()
    }

    private fun truncateTo(ply: Int) {
        while (game.moves.size > ply) {
            game.undo()
            qualities.remove(game.moves.size)
            winLoss.remove(game.moves.size)
        }
        visionBoard = game.current.copy()
    }

    fun pgn(): String = game.pgn()

    fun updateSettings(f: (Settings) -> Settings) {
        val old = _state.value.settings
        val new = f(old)
        store.save(new)
        autoOrientation = new.autoOrientation
        _state.update { it.copy(settings = new) }
        if (old.surface != new.surface) pipeline?.setSurface(new.surface)
        if (old.multiPv != new.multiPv || old.thinkTimeMs != new.thinkTimeMs || old.overlayMode != new.overlayMode) analyzeCurrent()
    }

    /** The analysis screen asks for longer, deeper searches. */
    fun setDeepAnalysis(deep: Boolean) {
        if (analysisDeep == deep) return
        analysisDeep = deep
        analyzeCurrent()
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    private fun banner(text: String, quality: MoveQuality? = null, error: Boolean = false) {
        _state.update { it.copy(banner = Banner(text, quality, error)) }
    }

    // ---------------------------------------------------------------- vision bridge

    fun rotateBoard() {
        val o = (_vision.value?.orientation ?: 0) + 1
        pipeline?.setOrientation(o and 3)
        pipeline?.requestResync(afterMotion = false)
    }

    fun flipBoard() {
        val o = (_vision.value?.orientation ?: 0) + 2
        pipeline?.setOrientation(o and 3)
        pipeline?.requestResync(afterMotion = false)
    }

    fun relock() {
        pipeline?.relock()
    }

    fun resync() {
        pipeline?.requestResync(afterMotion = false)
        banner("Board re-synced to the app position")
    }

    fun setManualCorners(view: List<Offset>) {
        val mapper = _vision.value?.mapper ?: return
        val pts = view.map { mapper.fromView(it) }
        pipeline?.setManualQuad(BoardQuad.ordered(pts))
    }

    /**
     * Called on the analyzer thread for every processed frame. [boardUsed] is the position the
     * frame was matched against; detections against a stale position are dropped.
     */
    fun onVisionFrame(frame: VisionFrame, mapper: FrameMapper?, boardUsed: Board) {
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val inst = 1e9f / (now - lastFrameNs).coerceAtLeast(1)
            fps = if (fps == 0f) inst else fps * 0.9f + inst * 0.1f
        }
        lastFrameNs = now
        val viewQuad = if (mapper != null) frame.quad?.corners?.map { mapper.toView(it) } else null
        val prevChanged = _vision.value?.changedSquares ?: emptyList()
        val changed = when (val e = frame.event) {
            is VisionEvent.Unrecognized -> e.squares
            is VisionEvent.MoveDetected, is VisionEvent.Resynced -> emptyList()
            else -> prevChanged
        }
        _vision.value = VisionUi(viewQuad, frame.phase, frame.orientation, frame.handOverBoard, fps, frame.processingMs, mapper, changed)

        if (frame.phase == TrackingPhase.LOCKED && lastPhase != TrackingPhase.LOCKED) _events.tryEmit(UiEvent.BoardLocked)
        lastPhase = frame.phase

        when (val e = frame.event) {
            is VisionEvent.MoveDetected -> viewModelScope.launch(Dispatchers.Main) {
                // Only accept if the app position hasn't changed meanwhile.
                if (boardUsed === visionBoard) {
                    if (!_state.value.isLive) refresh(viewPly = game.moves.size)
                    // A move and an instant reply arrive together; play them in order.
                    for (m in e.moves) {
                        if (m !in game.current.legalMoves()) break
                        playMove(m, fromCamera = true)
                    }
                }
            }
            is VisionEvent.Unrecognized -> viewModelScope.launch(Dispatchers.Main) {
                _events.tryEmit(UiEvent.Unrecognized(e.squares))
                banner("Couldn't read that move — tap it on the mini board", error = true)
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- analysis

    private fun boardAtView(): Board = game.boardAt(_state.value.viewPly)

    private fun analyzeCurrent() {
        val board = boardAtView()
        val fen = board.toFen()
        val settings = _state.value.settings
        val cached = evalCache[fen]
        _state.update { it.copy(analysis = cached, threat = null, searching = true) }
        if (board.status() != GameStatus.ONGOING && !board.hasLegalMove()) {
            engine.stop()
            _state.update { it.copy(searching = false) }
            return
        }
        val multiPv = maxOf(2, settings.multiPv)
        val time = if (analysisDeep) maxOf(settings.thinkTimeMs * 4L, 8000L) else settings.thinkTimeMs.toLong()
        engine.analyze(
            board,
            SearchLimits(maxDepth = 40, timeMs = time, multiPv = multiPv),
            onInfo = { info -> viewModelScope.launch(Dispatchers.Main) { onEngineInfo(fen, info, final = false) } },
            onDone = { info -> viewModelScope.launch(Dispatchers.Main) { onEngineInfo(fen, info, final = true) } },
        )
        analyzeThreat(board)
        cloudJob?.cancel()
        if (settings.cloudEval) {
            cloudJob = viewModelScope.launch {
                val cloud = CloudEval.fetch(board, multiPv) ?: return@launch
                onEngineInfo(fen, cloud, final = false)
            }
        }
    }

    private fun analyzeThreat(board: Board) {
        if (board.inCheck()) return
        val fen = board.toFen()
        val nullBoard = board.copy().also { it.makeNullMove() }
        val baseline = -Eval.evaluate(board)
        threatEngine.analyze(
            nullBoard,
            SearchLimits(maxDepth = 6, timeMs = 400),
            onInfo = {},
            onDone = { info ->
                val line = info.best ?: return@analyze
                // Significant only if passing would lose ~1.5 pawns.
                if (line.scoreCp - baseline >= 150 || line.mateIn?.let { it > 0 } == true) {
                    viewModelScope.launch(Dispatchers.Main) {
                        if (_state.value.position.fen == fen) _state.update { it.copy(threat = line) }
                    }
                }
            },
        )
    }

    private fun onEngineInfo(fen: String, info: EngineInfo, final: Boolean) {
        val prev = evalCache[fen]
        val better = prev == null || info.depth > prev.depth || (info.depth == prev.depth && info.source == prev.source)
        if (better && info.lines.isNotEmpty()) evalCache[fen] = info
        val current = _state.value
        if (current.position.fen == fen) {
            val shown = evalCache[fen] ?: info
            _state.update { it.copy(analysis = shown, searching = !final && it.searching) }
            if (final) _state.update { it.copy(searching = false) }
            maybeSpeak(fen, shown, final)
        }
        // Classification for any ply touching this position.
        for (ply in game.moves.indices) {
            if (qualities.containsKey(ply)) continue
            val before = if (ply == 0) game.startFen else game.moves[ply - 1].fenAfter
            val after = game.moves[ply].fenAfter
            if (before == fen || after == fen) tryClassify(ply, null)
        }
        updateEvalHistory()
    }

    private fun tryClassify(ply: Int, boardBefore: Board?) {
        if (ply !in game.moves.indices || qualities.containsKey(ply)) return
        val beforeFen = if (ply == 0) game.startFen else game.moves[ply - 1].fenAfter
        val afterFen = game.moves[ply].fenAfter
        val before = evalCache[beforeFen] ?: return
        val bd = boardBefore ?: game.boardAt(ply)
        val afterBoard = game.boardAt(ply + 1)
        val gameOver = !afterBoard.hasLegalMove()
        val after = evalCache[afterFen] ?: if (gameOver) EngineInfo(0, 0, 0, 0, emptyList(), afterBoard.side, afterFen) else return
        if (before.depth < 6 || (!gameOver && after.depth < 8)) return
        val move = game.moves[ply].move
        val q = MoveClassifier.classify(before, after, bd, move, ply)
        qualities[ply] = q
        val mover = bd.side
        val bestWin = Tactics.winPercent(povCp(before, mover))
        val playedWin = if (after.lines.isEmpty()) (if (afterBoard.inCheck()) 100.0 else 50.0) else Tactics.winPercent(povCp(after, mover))
        winLoss[ply] = (bestWin - playedWin).coerceAtLeast(0.0)
        val acc = accuracies()
        _state.update { it.copy(qualities = HashMap(qualities), accuracyWhite = acc.first, accuracyBlack = acc.second) }
        _events.tryEmit(UiEvent.Classified(ply, move, q))
        if (ply == game.moves.size - 1 && q in setOf(MoveQuality.BLUNDER, MoveQuality.MISTAKE, MoveQuality.BRILLIANT, MoveQuality.GREAT, MoveQuality.INACCURACY)) {
            val best = before.best?.move
            val bestSan = if (best != null && best != move) " · best was ${San.of(bd, best)}" else ""
            val mark = if (q.symbol.isNotEmpty()) "${q.symbol} " else ""
            banner("${game.moves[ply].san} $mark· ${q.label}$bestSan", q)
        }
    }

    private fun povCp(info: EngineInfo, pov: Int): Int {
        val w = info.whiteCp()
        return if (pov == Piece.WHITE) w else -w
    }

    /** Lichess-style per-move accuracy averaged per side. */
    private fun accuracies(): Pair<Double?, Double?> {
        fun acc(loss: Double) = (103.1668 * exp(-0.04354 * loss) - 3.1669).coerceIn(0.0, 100.0)
        val white = winLoss.filterKeys { game.moves.getOrNull(it)?.color == Piece.WHITE }.values.map(::acc)
        val black = winLoss.filterKeys { game.moves.getOrNull(it)?.color == Piece.BLACK }.values.map(::acc)
        return (if (white.isEmpty()) null else white.average()) to (if (black.isEmpty()) null else black.average())
    }

    private fun updateEvalHistory() {
        val list = ArrayList<Int?>(game.moves.size + 1)
        list += evalCache[game.startFen]?.whiteCp()
        for (m in game.moves) list += evalCache[m.fenAfter]?.whiteCp() ?: terminalEval(m.fenAfter)
        _state.update { it.copy(evalByPly = list) }
    }

    private fun terminalEval(fen: String): Int? {
        val b = Board.fromFen(fen)
        if (b.hasLegalMove()) return null
        return if (b.inCheck()) (if (b.side == Piece.WHITE) -10000 else 10000) else 0
    }

    private fun maybeSpeak(fen: String, info: EngineInfo, final: Boolean) {
        val s = _state.value
        if (!s.settings.voice || !s.isLive || fen in spokenFens) return
        if (!s.settings.isMyTurn(s.position.side)) return
        if (!(final || info.depth >= 12)) return
        val best = info.best?.move ?: return
        spokenFens += fen
        val board = Board.fromFen(fen)
        speech.say(San.spoken(board, best))
    }

    private fun refresh(viewPly: Int = _state.value.viewPly) {
        val ply = viewPly.coerceIn(0, game.moves.size)
        val board = game.boardAt(ply)
        val san = if (ply > 0) game.moves[ply - 1].san else null
        autoOrientation = _state.value.settings.autoOrientation && game.moves.isEmpty() && game.startFen == Board.START_FEN
        _state.update {
            it.copy(
                moves = game.moves.toList(),
                viewPly = ply,
                position = PositionSnapshot.of(board, san),
                startFen = game.startFen,
                qualities = HashMap(qualities),
            )
        }
        updateEvalHistory()
    }

    override fun onCleared() {
        engine.shutdown()
        threatEngine.shutdown()
        speech.shutdown()
        super.onCleared()
    }
}
