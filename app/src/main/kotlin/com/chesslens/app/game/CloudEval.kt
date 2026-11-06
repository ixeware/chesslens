package com.chesslens.app.game

import com.chesslens.chess.Board
import com.chesslens.chess.EngineInfo
import com.chesslens.chess.EngineLine
import com.chesslens.chess.Piece
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lichess cloud evaluation (deep Stockfish analysis cached for known positions).
 * Returns null when the position isn't in the database or the network is unavailable.
 */
object CloudEval {
    private val cache = HashMap<String, EngineInfo?>()

    suspend fun fetch(board: Board, multiPv: Int): EngineInfo? = withContext(Dispatchers.IO) {
        val fen = board.toFen()
        synchronized(cache) { if (cache.containsKey(fen)) return@withContext cache[fen] }
        val result = runCatching {
            val url = URL("https://lichess.org/api/cloud-eval?fen=" + URLEncoder.encode(fen, "UTF-8") + "&multiPv=$multiPv")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "ChessLens-Android")
            try {
                if (conn.responseCode != 200) return@runCatching null
                parse(board, conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        synchronized(cache) { cache[fen] = result }
        result
    }

    fun parse(board: Board, json: String): EngineInfo? {
        val o = JSONObject(json)
        val pvs = o.optJSONArray("pvs") ?: return null
        val lines = ArrayList<EngineLine>()
        for (i in 0 until pvs.length()) {
            val pv = pvs.getJSONObject(i)
            val bd = board.copy()
            val moves = ArrayList<Int>()
            for (uci in pv.optString("moves").split(' ').filter { it.isNotBlank() }) {
                val m = bd.parseUci(uci) ?: break
                bd.makeMove(m)
                moves += m
                if (moves.size >= 12) break
            }
            if (moves.isEmpty()) continue
            // Lichess reports scores from White's point of view; engine lines use side to move.
            val sign = if (board.side == Piece.WHITE) 1 else -1
            val line = if (pv.has("mate")) {
                val mate = pv.getInt("mate") * sign
                EngineLine(moves, if (mate > 0) 31_999 - mate else -31_999 - mate, mate)
            } else {
                EngineLine(moves, pv.optInt("cp") * sign, null)
            }
            lines += line
        }
        if (lines.isEmpty()) return null
        lines.sortByDescending { it.scoreCp }
        return EngineInfo(
            depth = o.optInt("depth"),
            selDepth = o.optInt("depth"),
            nodes = o.optLong("knodes") * 1000,
            timeMs = 0,
            lines = lines,
            sideToMove = board.side,
            fen = board.toFen(),
            source = "Cloud",
        )
    }
}
