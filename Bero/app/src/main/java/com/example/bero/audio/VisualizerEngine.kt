package com.example.bero.audio

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Espectro FFT en tiempo real a partir del audioSessionId de ExoPlayer.
 *
 * RENDIMIENTO: el Visualizer se crea DENTRO de un HandlerThread propio. La plataforma entrega los
 * callbacks en el looper del hilo que lo construyo, asi que toda la FFT (bucles sobre 512 bins,
 * logaritmos, normalizacion) se calcula fuera del hilo principal. La UI solo lee una referencia
 * atomica a un FloatArray ya terminado.
 *
 * Requiere RECORD_AUDIO (restriccion de la API Visualizer).
 */
class VisualizerEngine {
    companion object {
        const val BAR_COUNT = 32
    }

    private val thread = HandlerThread("bero-visualizer", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    /** Solo se toca desde el hilo del handler. */
    private var visualizer: Visualizer? = null

    @Volatile
    private var wantCapture = true

    private val latest = AtomicReference(FloatArray(BAR_COUNT))

    /** Ultimo espectro normalizado (0..1). Seguro de leer desde cualquier hilo. */
    fun snapshot(): FloatArray = latest.get()

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
            // No se usa: trabajamos con FFT.
        }

        // Se ejecuta en el HandlerThread, no en el hilo de UI.
        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft == null) return
            val n = fft.size / 2
            if (n < 8) return

            val lowBin = 1
            val highBin = min((n * 0.8f).toInt(), n - 1)
            val ratio = highBin.toFloat() / lowBin
            val out = FloatArray(BAR_COUNT)

            var prevEnd = lowBin
            for (i in 0 until BAR_COUNT) {
                val edgeStart = (lowBin * ratio.pow(i.toFloat() / BAR_COUNT)).toInt()
                val edgeEnd = (lowBin * ratio.pow((i + 1).toFloat() / BAR_COUNT)).toInt()
                var start = max(edgeStart, prevEnd)
                if (start > n - 1) start = n - 1
                var end = max(edgeEnd, start + 1)
                if (end > n) end = n
                prevEnd = end

                var peak = 0f
                for (k in start until end) {
                    val re = fft[2 * k].toFloat()
                    val im = fft[2 * k + 1].toFloat()
                    peak = max(peak, hypot(re, im))
                }
                val tilt = 1f + 0.9f * i / BAR_COUNT // compensa la menor energia en agudos
                out[i] = (sqrt((peak / 90f).coerceIn(0f, 1f)) * tilt).coerceIn(0f, 1f)
            }
            latest.set(out)
        }
    }

    /** (Re)crea el Visualizer para la sesion indicada. Libera el anterior. */
    fun start(sessionId: Int) {
        handler.post {
            releaseInternal()
            try {
                val v = Visualizer(sessionId)
                v.setEnabled(false)
                val range = Visualizer.getCaptureSizeRange()
                v.setCaptureSize(1024.coerceIn(range[0], range[1]))
                v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate(), false, true)
                v.setEnabled(wantCapture)
                visualizer = v
            } catch (e: Exception) {
                visualizer = null
            }
        }
    }

    /** Pausa/reanuda la captura sin destruir el Visualizer (no consume CPU mientras esta en pausa). */
    fun setCapturing(active: Boolean) {
        wantCapture = active
        handler.post {
            try {
                visualizer?.setEnabled(active)
            } catch (e: Exception) {
                // ignorar
            }
            if (!active) latest.set(FloatArray(BAR_COUNT))
        }
    }

    /** Libera el Visualizer y detiene el hilo. No usar el motor despues. */
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            releaseInternal()
            thread.quitSafely()
        }
    }

    private fun releaseInternal() {
        try {
            visualizer?.setEnabled(false)
            visualizer?.release()
        } catch (e: Exception) {
            // ignorar
        }
        visualizer = null
    }
}
