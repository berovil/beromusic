package com.example.bero.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    LIGHT("Claro"),
    DARK("Oscuro"),
    COLOR("Color"),
}

enum class ShuffleMode { OFF, ON, SMART }

/** Como se muestran las listas de canciones (pistas, favoritos, album y carpetas virtuales). */
enum class ViewMode(val label: String) {
    /** 1 columna = lista; 2..6 = cuadricula (se ajusta con el slider de la barra superior). */
    GRID("Lista / cuadrícula"),
    CAROUSEL("Carrusel"),
}

enum class VisualizerStyle(val label: String) {
    BARS("Barras"),
    WAVE("Onda"),
    DOTS("Puntos"),
    MIRROR("Espejo"),
    PEAKS("Picos"),
}

/** Colores rapidos; el selector libre (rueda cromatica) guarda cualquier ARGB. */
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

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, "") ?: "") }.getOrDefault(default)

    private val _themeMode = MutableStateFlow(readEnum("theme", ThemeMode.DARK))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // Migracion: antes se guardaba el NOMBRE del color; ahora se guarda el ARGB libre.
    private val _accentArgb = MutableStateFlow(
        if (prefs.contains("accent_argb")) {
            prefs.getInt("accent_argb", AccentColor.PURPLE.color.toArgb())
        } else {
            readEnum("accent", AccentColor.PURPLE).color.toArgb()
        }
    )
    val accentArgb: StateFlow<Int> = _accentArgb.asStateFlow()

    private val _gradient = MutableStateFlow(prefs.getBoolean("gradient_bg", false))
    val gradientBackground: StateFlow<Boolean> = _gradient.asStateFlow()

    // Migracion desde las versiones anteriores (LIST / GRID_3 / GRID_4 / CAROUSEL).
    private val legacyView = prefs.getString("view_mode", "") ?: ""

    private val _viewMode = MutableStateFlow(if (legacyView == "CAROUSEL") ViewMode.CAROUSEL else ViewMode.GRID)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _gridColumns = MutableStateFlow(
        if (prefs.contains("grid_columns")) {
            prefs.getInt("grid_columns", 1).coerceIn(1, 6)
        } else {
            when (legacyView) {
                "GRID_3" -> 3
                "GRID_4" -> 4
                else -> 1
            }
        }
    )
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private val _minSeconds = MutableStateFlow(prefs.getInt("min_seconds", 15))
    val minSeconds: StateFlow<Int> = _minSeconds.asStateFlow()

    private val _spectrogram = MutableStateFlow(prefs.getBoolean("spectrogram", true))
    val spectrogramEnabled: StateFlow<Boolean> = _spectrogram.asStateFlow()

    private val _visualizerStyle = MutableStateFlow(readEnum("visualizer_style", VisualizerStyle.BARS))
    val visualizerStyle: StateFlow<VisualizerStyle> = _visualizerStyle.asStateFlow()

    /** API key GRATUITA de Last.fm (solo lectura). No hay login de usuario. */
    private val _lastFmKey = MutableStateFlow(prefs.getString("lastfm_api_key", "") ?: "")
    val lastFmApiKey: StateFlow<String> = _lastFmKey.asStateFlow()

    private val _shuffleMode = MutableStateFlow(readEnum("shuffle_mode", ShuffleMode.OFF))
    val shuffleMode: StateFlow<ShuffleMode> = _shuffleMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun setAccentArgb(argb: Int) {
        _accentArgb.value = argb
        prefs.edit().putInt("accent_argb", argb).apply()
    }

    fun setGradientBackground(enabled: Boolean) {
        _gradient.value = enabled
        prefs.edit().putBoolean("gradient_bg", enabled).apply()
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
        prefs.edit().putString("view_mode", mode.name).apply()
    }

    fun setGridColumns(columns: Int) {
        val c = columns.coerceIn(1, 6)
        if (c == _gridColumns.value) return
        _gridColumns.value = c
        prefs.edit().putInt("grid_columns", c).apply()
    }

    fun setMinSeconds(seconds: Int) {
        _minSeconds.value = seconds
        prefs.edit().putInt("min_seconds", seconds).apply()
    }

    fun setSpectrogramEnabled(enabled: Boolean) {
        _spectrogram.value = enabled
        prefs.edit().putBoolean("spectrogram", enabled).apply()
    }

    fun setVisualizerStyle(style: VisualizerStyle) {
        _visualizerStyle.value = style
        prefs.edit().putString("visualizer_style", style.name).apply()
    }

    fun setLastFmApiKey(key: String) {
        val clean = key.trim()
        _lastFmKey.value = clean
        prefs.edit().putString("lastfm_api_key", clean).apply()
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
