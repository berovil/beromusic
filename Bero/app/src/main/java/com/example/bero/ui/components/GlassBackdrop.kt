package com.example.bero.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bero.data.BlurredArtwork
import com.example.bero.data.GlassQuality
import com.example.bero.data.Track
import com.example.bero.ui.theme.LocalGlassConfig

/**
 * Fondo de cristal del reproductor: la caratula, muy suavizada, bajo un velo del color del tema.
 *  - Rapido: no dibuja nada (solo queda el degradado de color del tema).
 *  - Equilibrado: caratula decodificada a 96 px y ampliada (el propio escalado la difumina; coste casi nulo).
 *  - Calidad: caratula desenfocada UNA vez por pista y cacheada ([BlurredArtwork]); ya no se aplica un
 *    RenderEffect en vivo sobre una capa a pantalla completa. Radio configurable (4..40 dp), funciona en cualquier API.
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
    val veil = remember(bg, veilTop, veilBottom) {
        Brush.verticalGradient(listOf(bg.copy(alpha = veilTop), bg.copy(alpha = veilBottom)))
    }

    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = track, animationSpec = tween(600), label = "glassBackdrop") { t ->
            if (quality == GlassQuality.QUALITY) {
                BlurredLayer(t, glass.frostDp)
            } else {
                Artwork(
                    track = t,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.3f
                            scaleY = 1.3f
                        },
                    corner = 0.dp,
                    px = 96
                )
            }
        }
        Box(Modifier.fillMaxSize().background(veil))
    }
}

@Composable
private fun BlurredLayer(track: Track, frostDp: Int) {
    val ctx = LocalContext.current
    val blurred by produceState<ImageBitmap?>(
        initialValue = BlurredArtwork.peek(track, frostDp)?.asImageBitmap(),
        track.id, frostDp
    ) {
        value = BlurredArtwork.get(ctx, track, frostDp)?.asImageBitmap()
    }
    val bmp = blurred
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        // Mientras se calcula (una sola vez por pista), la version equilibrada.
        Artwork(track = track, modifier = Modifier.fillMaxSize(), corner = 0.dp, px = 96)
    }
}
