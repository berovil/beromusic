package com.example.bero.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bero.data.Track
import com.example.bero.data.ViewMode
import kotlin.math.absoluteValue

/**
 * Una sola entrada para mostrar canciones segun el modo elegido en Ajustes:
 * lista, carrusel o cuadriculas (3x3 / 4x4). Todas usan `key` estable (id de pista) y `contentType`.
 */
@Composable
fun TrackCollection(
    tracks: List<Track>,
    currentId: Long?,
    viewMode: ViewMode,
    emptyText: String,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    onTrackMenu: ((Track) -> Unit)? = null,
    onPlay: (Int) -> Unit,
) {
    when (viewMode) {
        ViewMode.LIST -> TrackList(
            tracks = tracks,
            currentId = currentId,
            modifier = modifier,
            header = header,
            emptyText = emptyText,
            onTrackMenu = onTrackMenu,
            onPlay = onPlay
        )

        ViewMode.GRID_3 -> TrackGrid(3, tracks, currentId, emptyText, modifier, header, onTrackMenu, onPlay)
        ViewMode.GRID_4 -> TrackGrid(4, tracks, currentId, emptyText, modifier, header, onTrackMenu, onPlay)
        ViewMode.CAROUSEL -> TrackCarousel(tracks, currentId, emptyText, modifier, header, onTrackMenu, onPlay)
    }
}

// ------------------------------------------------------------------------ CUADRICULA ADAPTATIVA

@Composable
private fun TrackGrid(
    columns: Int,
    tracks: List<Track>,
    currentId: Long?,
    emptyText: String,
    modifier: Modifier,
    header: (@Composable () -> Unit)?,
    onTrackMenu: ((Track) -> Unit)?,
    onPlay: (Int) -> Unit,
) {
    val sidePadding = 14.dp
    val spacing = 10.dp
    val compact = columns >= 4

    BoxWithConstraints(modifier.fillMaxSize()) {
        // GridCells.Adaptive con el ancho minimo calculado para que quepan EXACTAMENTE `columns` columnas
        // (y mas si la pantalla es ancha / esta en horizontal): cuadricula ordenada y comoda en cualquier tamano.
        val minCell: Dp = ((maxWidth - sidePadding * 2 - spacing * (columns - 1)) / columns) - 0.5.dp

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCell),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            if (header != null) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Box(Modifier.padding(horizontal = 0.dp)) { header() }
                }
            }
            if (tracks.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        emptyText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "cell" }) { index, track ->
                TrackGridCell(
                    track = track,
                    isCurrent = track.id == currentId,
                    compact = compact,
                    modifier = Modifier
                        .animateItem()
                        .staggeredAppear(index, maxStaggered = columns * 3),
                    onLongClick = onTrackMenu?.let { menu -> { menu(track) } }
                ) { onPlay(index) }
            }
        }
    }
}

@Composable
private fun TrackGridCell(
    track: Track,
    isCurrent: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val corner = if (compact) 14.dp else 20.dp
    val shape = RoundedCornerShape(corner)
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            .longPressable(shape, 0.94f, onLongClick, onClick)
            .padding(4.dp)
    ) {
        Box {
            Artwork(
                track,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                corner = corner - 2.dp,
                px = if (compact) 220 else 340
            )
            if (isCurrent) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(2.dp, primary, RoundedCornerShape(corner - 2.dp))
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            track.title,
            fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isCurrent) primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            track.artist,
            fontSize = if (compact) 10.sp else 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ------------------------------------------------------------------------ CARRUSEL

@Composable
private fun TrackCarousel(
    tracks: List<Track>,
    currentId: Long?,
    emptyText: String,
    modifier: Modifier,
    header: (@Composable () -> Unit)?,
    onTrackMenu: ((Track) -> Unit)?,
    onPlay: (Int) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        header?.invoke()
        if (tracks.isEmpty()) {
            Text(
                emptyText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        val startPage = remember { tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0) }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { tracks.size })

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cardWidth = minOf(maxWidth * 0.70f, 300.dp)
            val sidePad = (maxWidth - cardWidth) / 2

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = sidePad),
                pageSpacing = 14.dp,
                key = { tracks[it].id },
                verticalAlignment = Alignment.CenterVertically
            ) { page ->
                val track = tracks[page]
                val shape = RoundedCornerShape(32.dp)
                Column(
                    Modifier
                        .width(cardWidth)
                        // La escala/transparencia se calculan en la capa grafica: deslizar NO recompone paginas.
                        .graphicsLayer {
                            val distance = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                .absoluteValue
                                .coerceIn(0f, 1f)
                            val scale = 0.82f + 0.18f * (1f - distance)
                            scaleX = scale
                            scaleY = scale
                            alpha = 0.55f + 0.45f * (1f - distance)
                        }
                        .longPressable(shape, 0.97f, onTrackMenu?.let { menu -> { menu(track) } }) { onPlay(page) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Artwork(
                        track,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        corner = 32.dp,
                        px = 700
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        track.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (track.id == currentId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        track.artist,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Text(
            "${pagerState.currentPage + 1} / ${tracks.size}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
