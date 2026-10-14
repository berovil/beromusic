package com.example.bero.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.LrcParser
import com.example.bero.data.LyricsState
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.pressable
import kotlin.math.abs

/** Letras estilo Apple Music / Spotify: izquierda, grandes y gruesas; la linea actual brilla; tocar una linea hace seekTo. */
@Composable
fun LyricsPanel(
    vm: BeroViewModel,
    state: LyricsState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LyricsState.Idle, LyricsState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = accent)
        }

        LyricsState.NotFound -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No se encontró letra para esta canción",
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }

        is LyricsState.Loaded -> LyricLines(vm, state, accent, modifier)
    }
}

@Composable
private fun LyricLines(
    vm: BeroViewModel,
    lyrics: LyricsState.Loaded,
    accent: Color,
    modifier: Modifier,
) {
    // El State de la posicion se pasa SIN leerse: derivedStateOf solo notifica cuando CAMBIA la linea actual,
    // asi que la lista no se recompone a 4 Hz, solo una vez por frase.
    val positionState = vm.positionMs.collectAsStateWithLifecycle()
    val currentState = remember(lyrics) {
        derivedStateOf {
            if (lyrics.synced) LrcParser.currentLineIndex(lyrics.lines, positionState.value) else -1
        }
    }
    val current by currentState
    val listState = rememberLazyListState()

    // Mantiene la linea actual en el primer tercio de la lista.
    LaunchedEffect(current, lyrics) {
        if (current >= 0) {
            val offset = -(listState.layoutInfo.viewportSize.height / 3)
            listState.animateScrollToItem(current, offset)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 80.dp, bottom = 240.dp)
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isCurrent = index == current
            val dimAlpha = when {
                !lyrics.synced -> 0.85f
                current < 0 -> 0.5f
                else -> (0.55f - 0.10f * (abs(index - current) - 1)).coerceIn(0.2f, 0.55f)
            }
            val color by animateColorAsState(
                targetValue = if (isCurrent) accent else Color.White.copy(alpha = dimAlpha),
                label = "lyricColor"
            )
            val glow = if (isCurrent) Shadow(color = accent.copy(alpha = 0.55f), blurRadius = 28f) else null

            Text(
                text = line.text.ifBlank { "♪" },
                color = color,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Start,
                style = TextStyle(shadow = glow),
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(RoundedCornerShape(16.dp), 0.97f) {
                        if (lyrics.synced) vm.seekTo(line.timeMs)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
