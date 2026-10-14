package com.example.bero.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.FolderGuard
import com.example.bero.data.Track
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.Artwork
import com.example.bero.ui.components.ScreenHeader
import com.example.bero.ui.components.TrackList
import com.example.bero.ui.components.TrackRow
import com.example.bero.ui.components.pressable

// ------------------------------------------------------------------------ PISTAS

@Composable
fun TracksScreen(vm: BeroViewModel, onTrackMenu: (Track) -> Unit, onOpenSettings: () -> Unit) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val hidden by vm.totalCount.collectAsStateWithLifecycle()

    TrackList(
        tracks = tracks,
        currentId = current?.id,
        header = {
            val subtitle = if (hidden > tracks.size) {
                "${tracks.size} canciones · ${hidden - tracks.size} ocultas por duración"
            } else {
                "${tracks.size} canciones"
            }
            ScreenHeader("Pistas", subtitle, onSettings = onOpenSettings)
        },
        emptyText = "No se encontró música. Revisa el filtro de duración en Ajustes o fuerza un escaneo.",
        onTrackMenu = onTrackMenu,
        onPlay = { index -> vm.playQueue(tracks, index) }
    )
}

// ------------------------------------------------------------------------ ÁLBUMES

@Composable
fun AlbumsScreen(vm: BeroViewModel, onTrackMenu: (Track) -> Unit, onOpenSettings: () -> Unit) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = albums.firstOrNull { it.id == selectedId }

    BackHandler(enabled = selected != null) { selectedId = null }

    if (selected == null) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ScreenHeader("Álbumes", "${albums.size} álbumes", onSettings = onOpenSettings)
            }
            items(albums, key = { it.id }) { album ->
                val shape = RoundedCornerShape(24.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pressable(shape, 0.95f) { selectedId = album.id }
                        .padding(6.dp)
                ) {
                    Artwork(
                        album.tracks.first(),
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        corner = 24.dp,
                        px = 400
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        album.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${album.artist} · ${album.tracks.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    } else {
        TrackList(
            tracks = selected.tracks,
            currentId = current?.id,
            header = {
                ScreenHeader(
                    title = selected.name,
                    subtitle = "${selected.artist} · ${selected.tracks.size} pistas",
                    onBack = { selectedId = null }
                )
            },
            onTrackMenu = onTrackMenu,
            onPlay = { index -> vm.playQueue(selected.tracks, index) }
        )
    }
}

// ------------------------------------------------------------------------ CARPETAS

@Composable
fun FoldersScreen(vm: BeroViewModel, onTrackMenu: (Track) -> Unit, onOpenSettings: () -> Unit) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val root = vm.storageRoot

    var rawPath by rememberSaveable { mutableStateOf(root) }
    // Defensa extra: si la ruta sale de la raiz o es una carpeta bloqueada, se vuelve a la raiz.
    val path = if (!rawPath.startsWith(root) || FolderGuard.isBlockedPath(rawPath)) root else rawPath
    val atRoot = path == root

    BackHandler(enabled = !atRoot) { rawPath = path.substringBeforeLast('/') }

    val prefix = "$path/"
    val (subfolders, here) = remember(tracks, path) {
        val counts = java.util.TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
        val direct = ArrayList<Track>()
        for (t in tracks) {
            if (!t.path.startsWith(prefix)) continue
            val rest = t.path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                direct.add(t)
            } else {
                val name = rest.substring(0, slash)
                if (!FolderGuard.isBlockedName(name)) counts[name] = (counts[name] ?: 0) + 1
            }
        }
        counts.entries.map { it.key to it.value } to direct.toList()
    }

    val title = if (atRoot) "Carpetas" else path.substringAfterLast('/')
    val subtitle = path.removePrefix(root).ifEmpty { "Memoria interna" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            ScreenHeader(
                title = title,
                subtitle = subtitle,
                onSettings = onOpenSettings,
                onBack = if (atRoot) null else ({ rawPath = path.substringBeforeLast('/') })
            )
        }
        if (subfolders.isEmpty() && here.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No hay audios en esta carpeta",
                    modifier = Modifier.padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(subfolders, key = { "folder:${it.first}" }) { (name, count) ->
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .pressable(shape, 0.97f) { rawPath = "$prefix$name" }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .padding(12.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$count pistas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        itemsIndexed(here, key = { _, t -> t.id }) { index, t ->
            TrackRow(t, t.id == current?.id, onLongClick = { onTrackMenu(t) }) { vm.playQueue(here, index) }
        }
    }
}

// ------------------------------------------------------------------------ FAVORITOS

@Composable
fun FavoritesScreen(vm: BeroViewModel, onTrackMenu: (Track) -> Unit, onOpenSettings: () -> Unit) {
    val favs by vm.favoriteTracks.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()

    TrackList(
        tracks = favs,
        currentId = current?.id,
        header = { ScreenHeader("Favoritos", "${favs.size} canciones", onSettings = onOpenSettings) },
        emptyText = "Aún no tienes favoritos. Toca el corazón en el reproductor.",
        onTrackMenu = onTrackMenu,
        onPlay = { index -> vm.playQueue(favs, index) }
    )
}
