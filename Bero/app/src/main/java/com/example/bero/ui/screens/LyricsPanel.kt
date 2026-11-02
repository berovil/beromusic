package com.example.bero.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.LrcParser
import com.example.bero.data.LyricLine
import com.example.bero.data.LyricsState
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.pressable
import com.example.bero.ui.theme.beroShape
import com.example.bero.ui.theme.beroWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Posicion vertical (fraccion del alto) donde se mantiene la linea activa. */
private const val ACTIVE_LINE_FRACTION = 0.28f

/** Opacidad de las lineas que no son la activa (uniforme, como en la referencia). */
private const val DIM_ALPHA = 0.38f

/**
 * Letras sincronizadas: la linea activa se ilumina y el resto queda atenuado. Si la fuente trae tiempos por
 * palabra, la linea activa se rellena progresivamente; si solo hay tiempos por linea, se ilumina entera.
 * Tocar una linea sincronizada hace seekTo.
 */
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
                color = fg.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }

        is LyricsState.Loaded -> LyricLines(vm, state, modifier)
    }
}

@Composable
private fun LyricLines(
    vm: BeroViewModel,
    lyrics: LyricsState.Loaded,
    modifier: Modifier,
) {
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val currentIdx = remember(lyrics) { mutableIntStateOf(-1) }
    // Caracteres "cantados" de la linea activa. Solo se LEE al dibujar: actualizarlo no recompone nada.
    val reveal = remember(lyrics) { mutableFloatStateOf(0f) }

    // Una sola lectura de la posicion real del reproductor por fotograma mientras suena (cuando esta en pausa,
    // cada 120 ms para reflejar un seek). Las escrituras solo ocurren si el valor cambia.
    LaunchedEffect(lyrics, playing) {
        while (isActive) {
            val pos = vm.currentPositionMs()
            val idx = if (lyrics.synced) LrcParser.currentLineIndex(lyrics.lines, pos) else -1
            if (idx != currentIdx.intValue) currentIdx.intValue = idx
            val r = if (idx >= 0 && lyrics.lines[idx].words != null) {
                LrcParser.revealChars(lyrics.lines, idx, pos)
            } else 0f
            if (r != reveal.floatValue) reveal.floatValue = r
            if (playing) withFrameNanos { } else delay(120)
        }
    }

    val listState = rememberLazyListState()
    var viewH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topPad = if (lyrics.synced && viewH > 0) with(density) { (viewH * ACTIVE_LINE_FRACTION).toDp() } else 24.dp
    val bottomPad = if (viewH > 0) with(density) { (viewH * 0.7f).toDp() } else 240.dp

    // Mantiene la linea activa en su posicion; el cambio de linea desplaza la lista con animacion.
    val current = currentIdx.intValue
    LaunchedEffect(current, lyrics, viewH > 0) {
        if (current >= 0 && viewH > 0) listState.animateScrollToItem(current, 0)
    }

    val bright = fg
    val dim = fg.copy(alpha = DIM_ALPHA)

    LazyColumn(
        state = listState,
        modifier = modifier
            .onSizeChanged { viewH = it.height }
            .verticalEdgeFade(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = topPad, bottom = bottomPad)
    ) {
        itemsIndexed(
            lyrics.lines,
            key = { index, _ -> index },
            contentType = { _, _ -> "line" }
        ) { index, line ->
            val isCurrent = index == current
            val hasWords = line.words != null
            val target = when {
                !lyrics.synced -> fg.copy(alpha = 0.85f)
                // Con tiempos por palabra la base es el gris y el relleno blanco lo dibuja encima.
                isCurrent && !hasWords -> bright
                else -> dim
            }
            val color by animateColorAsState(target, tween(250), label = "lyricColor")
            LyricText(
                line = line,
                color = color,
                fillColor = if (isCurrent && hasWords) bright else null,
                reveal = reveal,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(beroShape(16.dp), 0.97f) {
                        if (lyrics.synced) vm.seekTo(line.timeMs)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

/** Texto de una linea. Con [fillColor] dibuja encima, recortado hasta [reveal] caracteres, el texto "ya cantado". */
@Composable
private fun LyricText(
    line: LyricLine,
    color: Color,
    fillColor: Color?,
    reveal: FloatState,
    modifier: Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fillModifier = if (fillColor == null) Modifier else Modifier.drawWithContent {
        drawContent()
        val l = layout ?: return@drawWithContent
        val n = reveal.floatValue
        if (n <= 0f) return@drawWithContent
        val len = l.layoutInput.text.length
        val whole = n.toInt()
        if (whole >= len) {
            drawText(l, color = fillColor)
        } else {
            val path = if (whole > 0) l.getPathForRange(0, whole) else Path()
            val box = l.getBoundingBox(whole)
            path.addRect(Rect(box.left, box.top, box.left + box.width * (n - whole), box.bottom))
            clipPath(path) { drawText(l, color = fillColor) }
        }
    }
    Text(
        text = line.text.ifBlank { "♪" },
        color = color,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = beroWeight(FontWeight.Bold),
        textAlign = TextAlign.Start,
        onTextLayout = { if (fillColor != null) layout = it },
        modifier = modifier.then(fillModifier)
    )
}

/** Desvanece el contenido en los bordes superior e inferior (las lineas lejanas se funden con el fondo). */
private fun Modifier.verticalEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val top = 0.10f
        val bottom = 0.28f
        drawRect(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    top to Color.Black,
                    (1f - bottom) to Color.Black,
                    1f to Color.Transparent
                )
            ),
            blendMode = BlendMode.DstIn
        )
    }

/** Color de contenido del reproductor: SIGUE el modo Claro/Oscuro del tema (antes estaba fijo en blanco). */
private val fg: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalContentColor.current
