package com.example.bero.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.lastfm.ExternalSuggestion
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.QueueEntry
import com.example.bero.ui.QueueState
import com.example.bero.ui.components.Artwork
import com.example.bero.ui.components.BeroIconButton
import com.example.bero.ui.components.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val RowHeight = 68.dp

/** "Up Next" en un ModalBottomSheet. Mantener pulsada una pista y arrastrar la reordena en ExoPlayer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: BeroViewModel, accent: Color, onDismiss: () -> Unit) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val external by vm.externalSuggestions.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF14141A),
        contentColor = Color.White,
    ) {
        QueueContent(vm, queue, external, accent)
    }
}

@Composable
private fun QueueContent(
    vm: BeroViewModel,
    queue: QueueState,
    external: List<ExternalSuggestion>,
    accent: Color,
) {
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val lazyState = rememberLazyListState()
    val items = remember { mutableStateListOf<QueueEntry>().apply { addAll(queue.upNext) } }
    val dragState = remember {
        ReorderState(lazyState, items, rowPx) { from, to -> vm.moveQueueItem(from, to) }
    }

    // La lista local se re-sincroniza con ExoPlayer (verdad unica) salvo mientras se arrastra.
    LaunchedEffect(queue.upNext) {
        if (!dragState.isDragging) {
            items.clear()
            items.addAll(queue.upNext)
        }
    }

    // Auto-scroll al acercar el dedo a los bordes mientras se arrastra.
    val draggingKey = dragState.draggingKey
    LaunchedEffect(draggingKey) {
        if (draggingKey == null) return@LaunchedEffect
        while (isActive) {
            dragState.autoScrollStep()
            delay(16)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Fila de reproducción",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "Mantén pulsada una canción y arrástrala para reordenarla",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            // Remezcla lo que queda por sonar (en Smart Shuffle vuelve a intercalar sugerencias).
            BeroIconButton(
                icon = Icons.Rounded.Shuffle,
                contentDescription = "Mezclar la fila",
                onClick = vm::reshuffleUpcoming,
                accent = accent,
                tint = Color.White
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = lazyState,
            userScrollEnabled = !dragState.isDragging,
            modifier = Modifier.weight(1f)
        ) {
            item(key = "now") {
                Column {
                    SectionLabel("Reproduciendo ahora")
                    queue.current?.let { current ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(RowHeight)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Artwork(current, Modifier.size(48.dp), corner = 12.dp, px = 160)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    current.title,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    current.artist,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            item(key = "next_label") { SectionLabel("A continuación") }

            itemsIndexed(items, key = { _, entry -> entry.key }) { index, entry ->
                QueueRow(
                    entry = entry,
                    dragState = dragState,
                    accent = accent,
                    onPlay = { vm.playQueueIndex(queue.currentIndex + 1 + index) }
                )
            }

            if (queue.hiddenCount > 0) {
                item(key = "hidden") {
                    Text(
                        "+${queue.hiddenCount} canciones más en la fila",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }

            if (external.isNotEmpty()) {
                item(key = "ext_header") {
                    Column {
                        SectionLabel("Sugerencias de Last.fm (similares a tu música)")
                        Text(
                            "No están en tu biblioteca, así que no se pueden reproducir aquí. Tócalas para abrir su ficha en Last.fm.",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
                        )
                    }
                }
                items(external.take(15), key = { "ext:${it.artist}|${it.title}" }) { suggestion ->
                    ExternalRow(suggestion, accent) { vm.openSuggestion(suggestion) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun LazyItemScope.QueueRow(
    entry: QueueEntry,
    dragState: ReorderState,
    accent: Color,
    onPlay: () -> Unit,
) {
    val isDragging = dragState.draggingKey == entry.key
    val haptic = LocalHapticFeedback.current
    val latestOnPlay by rememberUpdatedState(onPlay)
    val shape = RoundedCornerShape(18.dp)

    // El desplazamiento se lee dentro del bloque de capa: arrastrar NO recompone la fila.
    val itemModifier = if (isDragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = dragState.dragOffset
                scaleX = 1.03f
                scaleY = 1.03f
            }
    } else {
        Modifier.animateItem()
    }

    Row(
        itemModifier
            .fillMaxWidth()
            .height(RowHeight)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(if (isDragging) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            // Toque = reproducir esta pista. (Tras una pulsacion larga el toque no se dispara.)
            .pointerInput(entry.key) { detectTapGestures(onTap = { latestOnPlay() }) }
            // Mantener pulsado y arrastrar = reordenar.
            .pointerInput(entry.key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        dragState.start(entry.key)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragState.dragBy(dragAmount.y)
                    },
                    onDragEnd = { dragState.end() },
                    onDragCancel = { dragState.end() }
                )
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(entry.track, Modifier.size(48.dp), corner = 12.dp, px = 160)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.track.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.track.artist,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.suggested) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = "Sugerencia",
                tint = accent,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp)
            )
        }
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}

@Composable
private fun ExternalRow(suggestion: ExternalSuggestion, accent: Color, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .pressable(shape, 0.97f, onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                suggestion.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                suggestion.artist,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Rounded.OpenInNew, contentDescription = "Abrir en Last.fm", tint = accent)
    }
}

/**
 * Reordenacion por arrastre con filas de ALTURA FIJA: la logica no depende de layoutInfo (que se queda
 * obsoleto un frame tras cada intercambio), solo de [dragOffset] relativo al hueco actual de la fila.
 * Durante el arrastre solo se mueve la lista local; al soltar se hace UNA sola llamada a
 * Player.moveMediaItem() (via ViewModel).
 */
@Stable
private class ReorderState(
    private val lazyState: LazyListState,
    private val items: SnapshotStateList<QueueEntry>,
    private val rowPx: Float,
    private val onDrop: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set

    var dragOffset by mutableFloatStateOf(0f)
        private set

    private var startIndex = -1

    val isDragging: Boolean get() = draggingKey != null

    fun start(key: String) {
        val index = items.indexOfFirst { it.key == key }
        if (index < 0) return
        draggingKey = key
        startIndex = index
        dragOffset = 0f
    }

    fun dragBy(dy: Float) {
        dragOffset += dy
        normalize()
    }

    /** Si el dedo cruzo la mitad de una fila vecina, intercambia y compensa el desplazamiento visual. */
    private fun normalize() {
        val key = draggingKey ?: return
        var index = items.indexOfFirst { it.key == key }
        if (index < 0) return
        while (dragOffset > rowPx / 2f && index < items.lastIndex) {
            items.add(index + 1, items.removeAt(index))
            index++
            dragOffset -= rowPx
        }
        while (dragOffset < -rowPx / 2f && index > 0) {
            items.add(index - 1, items.removeAt(index))
            index--
            dragOffset += rowPx
        }
        if (index == items.lastIndex) dragOffset = dragOffset.coerceAtMost(rowPx / 2f)
        if (index == 0) dragOffset = dragOffset.coerceAtLeast(-rowPx / 2f)
    }

    suspend fun autoScrollStep() {
        val key = draggingKey ?: return
        val info = lazyState.layoutInfo
        val me = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val center = me.offset + rowPx / 2f + dragOffset
        val edge = rowPx * 0.9f
        val delta = when {
            center < info.viewportStartOffset + edge -> -22f
            center > info.viewportEndOffset - edge -> 22f
            else -> 0f
        }
        if (delta != 0f) {
            val used = lazyState.scrollBy(delta)
            dragOffset += used // el contenido se mueve bajo el dedo: se compensa para no perder la fila
            normalize()
        }
    }

    fun end() {
        val key = draggingKey ?: return
        val to = items.indexOfFirst { it.key == key }
        val from = startIndex
        draggingKey = null
        dragOffset = 0f
        startIndex = -1
        if (from >= 0 && to >= 0 && from != to) onDrop(from, to)
    }
}
