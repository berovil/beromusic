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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bero.audio.VisualizerEngine
import kotlin.math.max

/**
 * Espectrograma digital, AISLADO del resto de la pantalla:
 *  - La FFT se calcula en un HandlerThread (ver VisualizerEngine).
 *  - Las barras suavizadas viven en un FloatArray que se muta en sitio (cero asignaciones por frame).
 *  - Un contador `frame` se LEE solo dentro del bloque de dibujo del Canvas, asi que cada frame solo
 *    invalida la fase de DIBUJO: ni este composable ni PlayerScreen se recomponen.
 *  - Cuando no suena nada y las barras terminaron de caer, el bucle de frames se detiene.
 * Siempre se pinta en blanco translucido sobre el fondo oscuro del reproductor: nunca se camufla.
 */
@Composable
fun SpectrumVisualizer(
    sessionId: Int,
    isPlaying: Boolean,
    hasPermission: Boolean,
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

    val display = remember { FloatArray(VisualizerEngine.BAR_COUNT) }
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
                if (next > 0.004f) moving = true
            }
            frame++
            if (!isPlaying && !moving) break
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE")
            val drawTick = frame // lectura de estado en la fase de dibujo: invalida solo el draw
            val n = display.size
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (n - 1)) / n
            val minHeight = 4.dp.toPx()
            for (i in 0 until n) {
                val v = display[i]
                val h = max(minHeight, v * size.height)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.55f + 0.40f * v),
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f)
                )
            }
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
