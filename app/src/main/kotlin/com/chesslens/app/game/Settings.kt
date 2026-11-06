package com.chesslens.app.game

import android.content.Context

enum class PlayAs(val label: String) { WHITE("White"), BLACK("Black"), BOTH("Both sides") }

enum class OverlayMode(val label: String) { HINT("Hints"), ANALYSIS("Analysis") }

data class Settings(
    val playAs: PlayAs = PlayAs.BOTH,
    val overlayMode: OverlayMode = OverlayMode.HINT,
    val arEnabled: Boolean = true,
    val voice: Boolean = true,
    val thinkTimeMs: Int = 2500,
    val multiPv: Int = 3,
    val showThreats: Boolean = true,
    val showHeatmap: Boolean = false,
    val showPv: Boolean = true,
    val cloudEval: Boolean = true,
    val autoOrientation: Boolean = true,
) {
    fun isMyTurn(side: Int): Boolean = when (playAs) {
        PlayAs.BOTH -> true
        PlayAs.WHITE -> side == 0
        PlayAs.BLACK -> side == 1
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("chesslens", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            playAs = runCatching { PlayAs.valueOf(prefs.getString("playAs", d.playAs.name)!!) }.getOrDefault(d.playAs),
            overlayMode = runCatching { OverlayMode.valueOf(prefs.getString("overlayMode", d.overlayMode.name)!!) }.getOrDefault(d.overlayMode),
            arEnabled = prefs.getBoolean("ar", d.arEnabled),
            voice = prefs.getBoolean("voice", d.voice),
            thinkTimeMs = prefs.getInt("thinkTime", d.thinkTimeMs),
            multiPv = prefs.getInt("multiPv", d.multiPv),
            showThreats = prefs.getBoolean("threats", d.showThreats),
            showHeatmap = prefs.getBoolean("heatmap", d.showHeatmap),
            showPv = prefs.getBoolean("pv", d.showPv),
            cloudEval = prefs.getBoolean("cloud", d.cloudEval),
            autoOrientation = prefs.getBoolean("autoOrientation", d.autoOrientation),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("playAs", s.playAs.name)
            .putString("overlayMode", s.overlayMode.name)
            .putBoolean("ar", s.arEnabled)
            .putBoolean("voice", s.voice)
            .putInt("thinkTime", s.thinkTimeMs)
            .putInt("multiPv", s.multiPv)
            .putBoolean("threats", s.showThreats)
            .putBoolean("heatmap", s.showHeatmap)
            .putBoolean("pv", s.showPv)
            .putBoolean("cloud", s.cloudEval)
            .putBoolean("autoOrientation", s.autoOrientation)
            .apply()
    }
}
