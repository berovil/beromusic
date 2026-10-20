package com.example.bero.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Ondas fluidas y muy discretas detras del mini-reproductor.
 *  - Dos capas: una levemente MAS CLARA y otra levemente MAS OSCURA que el contenedor (contraste suave alterno).
 *  - Se mueven solo con isPlaying; en pausa la amplitud se desvanece a un reposo casi plano.
 *  - Rendimiento: la fase (~30 fps) y la amplitud se leen SOLO en el bloque de dibujo => cada frame invalida el
 *    dibujo, nunca recompone; los Path se reutilizan; el bucle de frames existe solo mientras suena la musica.
 */
@Composable
fun MiniPlayerWave(isPlaying: Boolean, container: Color, modifier: Modifier = Modifier) {
    val phase = remember { mutableFloatStateOf(0f) }
    val amplitude = remember { Animatable(0.2f) }
    val pathA = remember { Path() }
    val pathB = remember { Path() }

    LaunchedEffect(isPlaying) {
        amplitude.animateTo(if (isPlaying) 1f else 0.2f, tween(700))
    }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        var accumulated = 0L
        while (true) {
            val now = withFrameNanos { it }
            accumulated += now - last
            last = now
            if (accumulated >= 33_000_000L) { // ~30 fps: suficiente para un efecto sutil y mas barato
                phase.floatValue += accumulated / 1_000_000_000f * 1.4f
                accumulated = 0L
            }
        }
    }

    val dark = container.luminance() < 0.5f
    val lighter = lerp(container, Color.White, if (dark) 0.07f else 0.55f)
    val darker = lerp(container, Color.Black, if (dark) 0.24f else 0.06f)

    Canvas(modifier) {
        val a = amplitude.value
        val p = phase.floatValue
        drawWave(pathA, size.height * 0.60f, size.height * 0.11f * a, size.width * 0.95f, p, lighter)
        drawWave(pathB, size.height * 0.74f, size.height * 0.09f * a, size.width * 0.60f, -p * 1.3f + 1.7f, darker)
    }
}

private fun DrawScope.drawWave(
    path: Path,
    baseline: Float,
    amplitudePx: Float,
    wavelength: Float,
    phase: Float,
    color: Color,
) {
    path.rewind()
    val step = 8.dp.toPx()
    val k = (2.0 * Math.PI / wavelength).toFloat()
    path.moveTo(0f, size.height)
    var x = 0f
    while (x <= size.width + step) {
        path.lineTo(x, baseline + sin(x * k + phase) * amplitudePx)
        x += step
    }
    path.lineTo(size.width, size.height)
    path.close()
    drawPath(path, color)
}
