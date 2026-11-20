package com.example.bero.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.TtmlLine
import com.example.bero.data.TtmlLyricsState
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.pressable
import com.example.bero.ui.theme.beroShape
import com.example.bero.ui.theme.beroWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

/** Posicion vertical (fraccion del alto) donde se mantiene la linea activa. */
private const val ANCHOR_FRACTION = 0.30f
private const val DIM_ALPHA = 0.36f
private const val SCROLL_MS = 560

/** Tras un gesto del usuario, el desplazamiento automatico espera este tiempo antes de recentrar. */
private const val USER_HOLD_MS = 3000L

/** Bandera sin estado de Compose: cambiarla no recompone nada. */
private class HoldFlag {
    var userHolding = false
}

private class LayoutHolder {
    var layout: TextLayoutResult? = null
}

/**
 * Letras TTML. La posicion viene del reproductor real (por fotograma mientras suena; cada 120 ms en pausa, lo que
 * refleja un seek). La lista conserva estado (keys estables); la linea activa y el progreso por sílaba/palabra solo
 * se LEEN al dibujar o por item, asi que avanzar el audio no recompone la lista. Sin tiempos por palabra se ilumina
 * la linea entera; sin tiempos de ningun tipo se muestra el texto sin fingir sincronizacion.
 */
@Composable
fun TtmlLyricsPanel(
    vm: BeroViewModel,
    state: TtmlLyricsState.Loaded,
    modifier: Modifier = Modifier,
) {
    // key(doc): al cambiar de cancion se descarta el estado de scroll y todo lo demas.
    key(System.identityHashCode(state.doc)) { TtmlLyricsContent(vm, state, modifier) }
}

@Composable
private fun TtmlLyricsContent(
    vm: BeroViewModel,
    state: TtmlLyricsState.Loaded,
    modifier: Modifier,
) {
    val doc = state.doc
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val activeIdx = remember { mutableIntStateOf(-1) }
    val posMs = remember { mutableLongStateOf(0L) }

    LaunchedEffect(playing) {
        while (isActive) {
            val pos = vm.currentPositionMs()
            if (pos != posMs.longValue) posMs.longValue = pos
            val idx = if (doc.synced) doc.activeIndex(pos) else -1
            if (idx != activeIdx.intValue) activeIdx.intValue = idx
            if (playing) withFrameNanos { } else delay(120)
        }
    }

    val listState = rememberLazyListState()
    var viewH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val anchorPx = viewH * ANCHOR_FRACTION
    val anchorState = rememberUpdatedState(anchorPx)
    val topSpacer = if (doc.synced && viewH > 0) with(density) { anchorPx.toDp() } else 24.dp
    val bottomSpacer = if (viewH > 0) with(density) { (viewH * 0.65f).toDp() } else 240.dp
    val hold = remember { HoldFlag() }

    // Desplazamiento: cada cambio de linea activa recentra la lista con una animacion continua.
    // El primer posicionamiento (abrir el panel a mitad de cancion, seek lejano) es instantaneo.
    LaunchedEffect(viewH > 0) {
        if (!doc.synced || viewH <= 0) return@LaunchedEffect
        var first = true
        snapshotFlow { activeIdx.intValue }.collectLatest { idx ->
            if (idx < 0) {
                if (!first && !hold.userHolding) listState.animateScrollToItem(0)
            } else if (!hold.userHolding) {
                listState.centerOn(idx, anchorState.value, animate = !first)
                first = false
            }
        }
    }

    // Si el usuario arrastra la lista, no se pelea con el: se recentra 3 s despues de soltar.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { i ->
            when (i) {
                is DragInteraction.Start -> hold.userHolding = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    delay(USER_HOLD_MS)
                    hold.userHolding = false
                    val idx = activeIdx.intValue
                    if (doc.synced && idx >= 0) listState.centerOn(idx, anchorState.value, animate = true)
                }
            }
        }
    }

    val fg = LocalContentColor.current
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        state = listState,
        modifier = modifier
            .onSizeChanged { viewH = it.height }
            .ttmlEdgeFade(),
    ) {
        item(key = "top", contentType = "spacer") { Spacer(Modifier.height(topSpacer)) }
        items(doc.lines, key = { it.id }, contentType = { "line" }) { line ->
            TtmlLineItem(
                line = line,
                synced = doc.synced,
                activeIdx = activeIdx,
                pos = posMs,
                fg = fg,
                onClick = { if (doc.synced && line.startMs >= 0) vm.seekTo(line.startMs) }
            )
        }
        item(key = "credit", contentType = "credit") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    state.credit,
                    color = fg.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                state.creditLinks.forEach { link ->
                    Text(
                        text = link.label,
                        color = fg.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clickable { runCatching { uriHandler.openUri(link.url) } }
                            .padding(top = 4.dp)
                    )
                }
            }
        }
        item(key = "bottom", contentType = "spacer") { Spacer(Modifier.height(bottomSpacer)) }
    }
}

/**
 * Coloca la linea [lineIndex] con su borde superior en [anchorPx]. Mide la posicion real del item (alturas variables,
 * frases en varias lineas) en lugar de asumir una altura fija. +1 por el espaciador superior.
 */
private suspend fun LazyListState.centerOn(lineIndex: Int, anchorPx: Float, animate: Boolean) {
    val itemIndex = lineIndex + 1
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
    if (visible == null) {
        // Fuera de pantalla (seek lejano o apertura): salto directo ya colocado en el ancla.
        scrollToItem(itemIndex, -anchorPx.roundToInt())
        return
    }
    val delta = visible.offset - anchorPx
    if (abs(delta) < 1f) return
    if (animate) animateScrollBy(delta, tween(SCROLL_MS, easing = FastOutSlowInEasing)) else scrollBy(delta)
}

@Composable
private fun TtmlLineItem(
    line: TtmlLine,
    synced: Boolean,
    activeIdx: IntState,
    pos: LongState,
    fg: Color,
    onClick: () -> Unit,
) {
    // Solo los items cuya condicion cambia se recomponen (2 por cambio de linea), no toda la lista.
    val isActive by remember(line.id, synced) { derivedStateOf { synced && activeIdx.intValue == line.id } }
    val activeness = animateFloatAsState(if (isActive) 1f else 0f, tween(320), label = "ttmlActive")
    val align = if (line.agentIndex % 2 == 1) TextAlign.End else TextAlign.Start

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // La linea activa destaca por escala (sin reflujo del texto) ademas de por brillo.
                val s = 0.93f + 0.07f * activeness.value
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(if (align == TextAlign.End) 1f else 0f, 0.5f)
            }
            .pressable(beroShape(16.dp), 0.97f, onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        LyricBlock(line, 28.sp, 34.sp, synced, activeness, pos, fg, align)
        line.background?.let { bg ->
            Spacer(Modifier.height(4.dp))
            LyricBlock(bg, 20.sp, 26.sp, synced, activeness, pos, fg, align)
        }
        line.translation?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = fg.copy(alpha = 0.55f), fontSize = 16.sp, lineHeight = 21.sp, textAlign = align, modifier = Modifier.fillMaxWidth())
        }
        line.roman?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = fg.copy(alpha = 0.42f), fontSize = 15.sp, lineHeight = 20.sp, textAlign = align, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Texto base atenuado + capa brillante dibujada encima, recortada hasta el caracter "cantado" en este instante.
 * [pos] y [activeness] solo se leen en la fase de dibujo: el avance por sílaba no provoca recomposicion.
 */
@Composable
private fun LyricBlock(
    line: TtmlLine,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    synced: Boolean,
    activeness: androidx.compose.runtime.State<Float>,
    pos: LongState,
    fg: Color,
    align: TextAlign,
) {
    val holder = remember { LayoutHolder() }
    val base = if (synced) fg.copy(alpha = DIM_ALPHA) else fg.copy(alpha = 0.85f)
    Text(
        text = line.text.ifBlank { "♪" },
        color = base,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = beroWeight(FontWeight.Bold),
        textAlign = align,
        onTextLayout = { holder.layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!synced) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val a = activeness.value
                    if (a <= 0.003f) return@drawWithContent
                    val l = holder.layout ?: return@drawWithContent
                    val color = fg.copy(alpha = a)
                    val len = l.layoutInput.text.length
                    // Sin tiempos por sílaba/palabra: la linea entera se ilumina con la linea activa.
                    val n = if (line.syllables.isEmpty()) len.toFloat() else line.revealChars(pos.longValue)
                    if (n <= 0f) return@drawWithContent
                    val whole = n.toInt().coerceIn(0, len)
                    if (whole >= len) {
                        drawText(l, color = color)
                    } else {
                        val path = if (whole > 0) l.getPathForRange(0, whole) else Path()
                        val box = l.getBoundingBox(whole)
                        path.addRect(Rect(box.left, box.top, box.left + box.width * (n - whole), box.bottom))
                        clipPath(path) { drawText(l, color = color) }
                    }
                }
            )
    )
}

/** Desvanece el contenido en los bordes superior e inferior (las lineas lejanas se funden con el fondo). */
private fun Modifier.ttmlEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.10f to Color.Black,
                    0.72f to Color.Black,
                    1f to Color.Transparent
                )
            ),
            blendMode = BlendMode.DstIn
        )
    }
