package com.example.bero.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ecualizador interno (android.media.audiofx.Equalizer) enlazado a la sesion de audio de ExoPlayer.
 * Vive en el proceso del servicio y la UI lo controla a traves de estos StateFlow.
 * No usa Intents del sistema, asi que funciona aunque HyperOS los restrinja.
 */
object AudioFx {
    data class Band(val index: Int, val centerHz: Int, val level: Int)

    data class EqState(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val minLevel: Int = -1500,
        val maxLevel: Int = 1500,
        val bands: List<Band> = emptyList(),
        val presets: List<String> = emptyList(),
        val currentPreset: Int = -1,
    )

    private val _sessionId = MutableStateFlow(0)
    val sessionId: StateFlow<Int> = _sessionId.asStateFlow()

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var equalizer: Equalizer? = null
    private var presetIndex = -1

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("bero_eq", Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) return
        _sessionId.value = audioSessionId
        releaseEqualizer()
        try {
            val eq = Equalizer(0, audioSessionId)
            val p = prefs
            eq.setEnabled(p?.getBoolean("enabled", false) ?: false)
            val range = eq.bandLevelRange
            val bandCount = eq.numberOfBands.toInt()
            for (b in 0 until bandCount) {
                val saved = p?.getInt("band_$b", Int.MIN_VALUE) ?: Int.MIN_VALUE
                if (saved != Int.MIN_VALUE) {
                    val clamped = saved.coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(b.toShort(), clamped.toShort())
                }
            }
            equalizer = eq
        } catch (e: Exception) {
            equalizer = null
        }
        publish()
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        try {
            equalizer?.setEnabled(enabled)
            prefs?.edit()?.putBoolean("enabled", enabled)?.apply()
        } catch (e: Exception) {
            // ignorar
        }
        publish()
    }

    @Synchronized
    fun setBand(index: Int, level: Int) {
        try {
            val eq = equalizer ?: return
            val range = eq.bandLevelRange
            val clamped = level.coerceIn(range[0].toInt(), range[1].toInt())
            eq.setBandLevel(index.toShort(), clamped.toShort())
            prefs?.edit()?.putInt("band_$index", clamped)?.apply()
            presetIndex = -1
        } catch (e: Exception) {
            // ignorar
        }
        publish()
    }

    @Synchronized
    fun usePreset(index: Int) {
        try {
            val eq = equalizer ?: return
            eq.usePreset(index.toShort())
            presetIndex = index
            val editor = prefs?.edit()
            for (b in 0 until eq.numberOfBands.toInt()) {
                editor?.putInt("band_$b", eq.getBandLevel(b.toShort()).toInt())
            }
            editor?.apply()
        } catch (e: Exception) {
            // ignorar
        }
        publish()
    }

    @Synchronized
    fun reset() {
        try {
            val eq = equalizer ?: return
            val editor = prefs?.edit()
            for (b in 0 until eq.numberOfBands.toInt()) {
                eq.setBandLevel(b.toShort(), 0)
                editor?.putInt("band_$b", 0)
            }
            editor?.apply()
            presetIndex = -1
        } catch (e: Exception) {
            // ignorar
        }
        publish()
    }

    @Synchronized
    fun release() {
        releaseEqualizer()
        publish()
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            // ignorar
        }
        equalizer = null
        presetIndex = -1
    }

    private fun publish() {
        val eq = equalizer
        if (eq == null) {
            _state.value = EqState(available = false)
            return
        }
        try {
            val range = eq.bandLevelRange
            val bands = (0 until eq.numberOfBands.toInt()).map { b ->
                Band(
                    index = b,
                    centerHz = eq.getCenterFreq(b.toShort()) / 1000,
                    level = eq.getBandLevel(b.toShort()).toInt(),
                )
            }
            val presets = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
            _state.value = EqState(
                available = true,
                enabled = eq.getEnabled(),
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                bands = bands,
                presets = presets,
                currentPreset = presetIndex,
            )
        } catch (e: Exception) {
            _state.value = EqState(available = false)
        }
    }
}
