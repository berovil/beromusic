package com.example.bero.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.bero.data.GlassQuality
import com.example.bero.data.Track
import com.example.bero.ui.theme.LocalGlassConfig

/**
 * Fondo de cristal del reproductor: la caratula, muy suavizada, bajo un velo del color del tema.
 *  - Rapido: no dibuja nada (solo queda el degradado de color del tema).
 *  - Equilibrado: caratula decodificada a 96 px y ampliada (el propio escalado la difumina; coste casi nulo).
 *  - Calidad (Android 12+): ademas aplica un desenfoque real (RenderEffect) de radio configurable.
 * Se amplia un 30 % para que los bordes del desenfoque queden fuera de pantalla.
 */
@Composable
fun GlassArtworkBackdrop(track: Track, modifier: Modifier = Modifier) {
    val glass = LocalGlassConfig.current
    val quality = glass.effectiveQuality
    if (quality == GlassQuality.FAST) return

    val bg = MaterialTheme.colorScheme.background
    val dark = bg.luminance() < 0.5f
    val veilTop = if (dark) 0.45f else 0.50f
    val veilBottom = if (dark) 0.80f else 0.82f

    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = track, animationSpec = tween(600), label = "glassBackdrop") { t ->
            Artwork(
                track = t,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                    }
                    .then(if (quality == GlassQuality.QUALITY) Modifier.blur(glass.frostDp.dp) else Modifier),
                corner = 0.dp,
                px = 96
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(bg.copy(alpha = veilTop), bg.copy(alpha = veilBottom))))
        )
    }
}
