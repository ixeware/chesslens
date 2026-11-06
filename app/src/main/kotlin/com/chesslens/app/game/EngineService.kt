package com.chesslens.app.game

import com.chesslens.chess.Board
import com.chesslens.chess.EngineInfo
import com.chesslens.chess.Search
import com.chesslens.chess.SearchLimits
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the Kotlin engine on a dedicated background thread. Starting a new analysis
 * cancels the previous one; stale callbacks are dropped.
 */
class EngineService(ttSizeLog2: Int = 20, name: String = "engine") {
    private val search = Search(ttSizeLog2)
    private val generation = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun analyze(
        board: Board,
        limits: SearchLimits,
        onInfo: (EngineInfo) -> Unit,
        onDone: (EngineInfo) -> Unit = {},
    ) {
        val gen = generation.incrementAndGet()
        search.stop()
        val snapshot = board.copy()
        executor.execute {
            if (gen != generation.get()) return@execute
            val result = search.run(snapshot, limits) { info ->
                if (gen == generation.get()) onInfo(info)
            }
            if (gen == generation.get()) onDone(result)
        }
    }

    fun stop() {
        generation.incrementAndGet()
        search.stop()
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
    }
}
