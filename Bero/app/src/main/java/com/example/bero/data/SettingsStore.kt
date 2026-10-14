package com.example.bero.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    LIGHT("Claro"),
    DARK("Oscuro"),
    COLOR("Color"),
}

enum class ShuffleMode { OFF, ON, SMART }

enum class AccentColor(val label: String, val color: Color) {
    PURPLE("Morado", Color(0xFF9B7BFF)),
    BLUE("Azul", Color(0xFF4C8DFF)),
    CYAN("Cian", Color(0xFF22B8CF)),
    GREEN("Verde", Color(0xFF3DDC84)),
    ORANGE("Naranja", Color(0xFFFF9F43)),
    PINK("Rosa", Color(0xFFFF5C93)),
    RED("Rojo", Color(0xFFFF5252)),
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bero_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", "") ?: "") }.getOrDefault(ThemeMode.DARK)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _accent = MutableStateFlow(
        runCatching { AccentColor.valueOf(prefs.getString("accent", "") ?: "") }.getOrDefault(AccentColor.PURPLE)
    )
    val accent: StateFlow<AccentColor> = _accent.asStateFlow()

    private val _minSeconds = MutableStateFlow(prefs.getInt("min_seconds", 15))
    val minSeconds: StateFlow<Int> = _minSeconds.asStateFlow()

    private val _spectrogram = MutableStateFlow(prefs.getBoolean("spectrogram", true))
    val spectrogramEnabled: StateFlow<Boolean> = _spectrogram.asStateFlow()

    private val _spotifyClientId = MutableStateFlow(prefs.getString("spotify_client_id", "") ?: "")
    val spotifyClientId: StateFlow<String> = _spotifyClientId.asStateFlow()

    private val _shuffleMode = MutableStateFlow(
        runCatching { ShuffleMode.valueOf(prefs.getString("shuffle_mode", "") ?: "") }.getOrDefault(ShuffleMode.OFF)
    )
    val shuffleMode: StateFlow<ShuffleMode> = _shuffleMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun setAccent(accent: AccentColor) {
        _accent.value = accent
        prefs.edit().putString("accent", accent.name).apply()
    }

    fun setMinSeconds(seconds: Int) {
        _minSeconds.value = seconds
        prefs.edit().putInt("min_seconds", seconds).apply()
    }

    fun setSpectrogramEnabled(enabled: Boolean) {
        _spectrogram.value = enabled
        prefs.edit().putBoolean("spectrogram", enabled).apply()
    }

    fun setSpotifyClientId(id: String) {
        val clean = id.trim()
        _spotifyClientId.value = clean
        prefs.edit().putString("spotify_client_id", clean).apply()
    }

    fun setShuffleMode(mode: ShuffleMode) {
        _shuffleMode.value = mode
        prefs.edit().putString("shuffle_mode", mode.name).apply()
    }

    // ---- Persistencia de la ultima cola de reproduccion ----

    fun saveQueue(ids: List<Long>) {
        prefs.edit().putString("queue", ids.joinToString(",")).apply()
    }

    fun loadQueue(): List<Long> =
        (prefs.getString("queue", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }

    fun saveProgress(trackId: Long, positionMs: Long) {
        prefs.edit().putLong("last_id", trackId).putLong("last_pos", positionMs).apply()
    }

    fun lastTrackId(): Long = prefs.getLong("last_id", -1L)

    fun lastPosition(): Long = prefs.getLong("last_pos", 0L)
}
