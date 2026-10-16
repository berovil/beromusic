package com.example.bero.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bero.audio.VisualizerEngine
import com.example.bero.data.VisualizerStyle
import kotlin.math.max

/**
 * Espectrograma digital, AISLADO del resto de la pantalla:
 *  - La FFT se calcula en un HandlerThread (ver VisualizerEngine).
 *  - Las barras suavizadas viven en FloatArrays que se mutan en sitio (cero asignaciones por frame).
 *  - Un contador `frame` se LEE solo dentro del bloque de dibujo del Canvas: cada frame solo invalida la
 *    fase de DIBUJO; ni este composable ni PlayerScreen se recomponen.
 *  - Sin audio y con las barras ya caidas, el bucle de frames se detiene.
 *  - Al pasar la app a segundo plano se pausa la captura del Visualizer (ON_STOP) y se reanuda en ON_START.
 * Usa el color principal del tema (aclarado por la pantalla para que contraste con el fondo oscuro).
 */
@Composable
fun SpectrumVisualizer(
    sessionId: Int,
    isPlaying: Boolean,
    hasPermission: Boolean,
    style: VisualizerStyle,
    color: Color,
    onNeedPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = remember { VisualizerEngine() }

    // Se libera el Visualizer y el hilo al salir de composicion (pantalla cerrada, modo letras o ajuste desactivado).
    DisposableEffect(engine) {
        onDispose { engine.shutdown() }
    }
    DisposableEffect(engine, sessionId, hasPermission) {
        if (hasPermission && sessionId != 0) engine.start(sessionId)
        onDispose { }
    }
    LaunchedEffect(engine, isPlaying, hasPermission) {
        engine.setCapturing(isPlaying && hasPermission)
    }

    // Segundo plano: no capturar ni gastar CPU si la UI no es visible.
    val wantCapture by rememberUpdatedState(isPlaying && hasPermission)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> engine.setCapturing(false)
                Lifecycle.Event.ON_START -> engine.setCapturing(wantCapture)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val display = remember { FloatArray(VisualizerEngine.BAR_COUNT) }
    val peaks = remember { FloatArray(VisualizerEngine.BAR_COUNT) }
    val scratch = remember { SpectrumScratch() }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(engine, isPlaying, hasPermission) {
        while (true) {
            withFrameNanos { }
            val target = if (isPlaying && hasPermission) engine.snapshot() else null
            var moving = false
            for (i in display.indices) {
                val t = if (target != null && i < target.size) target[i] else 0f
                val c = display[i]
                val next = if (t > c) c + (t - c) * 0.6f else c + (t - c) * 0.12f
                display[i] = next
                peaks[i] = max(next, peaks[i] - 0.015f) // los "picos" caen lentamente
                if (next > 0.004f || peaks[i] > 0.004f) moving = true
            }
            frame++
            if (!isPlaying && !moving) break
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE")
            val drawTick = frame // lectura de estado en la fase de dibujo: invalida solo el draw
            drawSpectrum(style, display, peaks, color, scratch)
        }
        if (!hasPermission) {
            Text(
                "Toca para activar el visualizador (permiso de micrófono)",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .clickable(onClick = onNeedPermission)
                    .padding(8.dp)
            )
        }
    }
}
