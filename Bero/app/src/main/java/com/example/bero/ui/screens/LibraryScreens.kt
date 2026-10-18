package com.example.bero.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.FolderGuard
import com.example.bero.data.Track
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.Artwork
import com.example.bero.ui.components.BeroChip
import com.example.bero.ui.components.GridControls
import com.example.bero.ui.components.LibraryBarDefaults
import com.example.bero.ui.components.LibraryScaffold
import com.example.bero.ui.components.TrackCollection
import com.example.bero.ui.components.TrackRow
import com.example.bero.ui.components.filterAlbumsByQuery
import com.example.bero.ui.components.rememberSearchResults
import com.example.bero.ui.components.matchesQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.example.bero.ui.components.pressable
import com.example.bero.ui.components.rememberLibraryBarState
import com.example.bero.ui.components.rememberSearchQuery
import com.example.bero.ui.components.staggeredAppear
import com.example.bero.data.ViewMode

private val BarHeight = LibraryBarDefaults.Height

/** Controles del slider de la barra superior, enlazados a los ajustes (persisten entre sesiones). */
@Composable
private fun rememberGridControls(vm: BeroViewModel): GridControls {
    val columns by vm.settings.gridColumns.collectAsStateWithLifecycle()
    val mode by vm.settings.viewMode.collectAsStateWithLifecycle()
    return remember(columns, mode, vm) {
        GridControls(
            columns = columns,
            carousel = mode == ViewMode.CAROUSEL,
            onColumns = vm.settings::setGridColumns,
            onCarousel = { carousel ->
                vm.settings.setViewMode(if (carousel) ViewMode.CAROUSEL else ViewMode.GRID)
            }
        )
    }
}

// ------------------------------------------------------------------------ PISTAS

@Composable
fun TracksScreen(vm: BeroViewModel, onTrackMenu: (Track) -> Unit, onOpenSettings: () -> Unit) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val controls = rememberGridControls(vm)

    val bar = rememberLibraryBarState()
    val query = rememberSearchQuery(bar)
    val shown = rememberSearchResults(tracks, query)

    val subtitle = when {
        query.isNotEmpty() -> "${shown.size} de ${tracks.size} canciones"
        total > tracks.size -> "${tracks.size} canciones · ${total - tracks.size} ocultas por duración"
        else -> "${tracks.size} canciones"
    }

    LibraryScaffold("Pistas", subtitle, bar, onOpenSettings, gridControls = controls) {
        TrackCollection(
            tracks = shown,
            currentId = current?.id,
            viewMode = controls.viewMode,
            columns = controls.columns,
            topPadding = BarHeight,
            emptyText = if (query.isNotEmpty()) "Sin resultados para tu búsqueda."
            else "No se encontró música. Revisa el filtro de duración en Ajustes o fuerza un escaneo.",
            onTrackMenu = onTrackMenu,
            onPlay = { index -> vm.playQueue(shown, index) }
        )
    }
}

// ------------------------------------------------------------------------ ÁLBUMES

@Composable
fun AlbumsScreen(vm: BeroViewModel, onTrackMenu: (Track) -> Unit, onOpenSettings: () -> Unit) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val controls = rememberGridControls(vm)
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }

    val bar = rememberLibraryBarState()
    val query = rememberSearchQuery(bar)
    val selectedAlbum = albums.firstOrNull { it.id == selectedId }

    BackHandler(enabled = selectedId != null) {
        selectedId = null
        bar.closeSearch()
    }

    val shownAlbums = remember(albums, query) { albums.filterAlbumsByQuery(query) }

    LibraryScaffold(
        title = selectedAlbum?.name ?: "Álbumes",
        subtitle = selectedAlbum?.let { "${it.artist} · ${it.tracks.size} pistas" } ?: "${albums.size} álbumes",
        state = bar,
        onOpenSettings = onOpenSettings,
        onBack = if (selectedId != null) ({
            selectedId = null
            bar.closeSearch()
        }) else null,
        gridControls = if (selectedId != null) controls else null
    ) {
        // Transicion suave entre la cuadricula de albumes y el detalle.
        AnimatedContent(
            targetState = selectedId,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 6 } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(160)))
                }
            },
            label = "albumsNav"
        ) { id ->
            val selected = albums.firstOrNull { it.id == id }
            if (selected == null) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = BarHeight + 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(shownAlbums, key = { it.id }, contentType = { "album" }) { album ->
                        val shape = RoundedCornerShape(24.dp)
                        Column(
                            Modifier
                                .animateItem()
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
                val shownTracks = rememberSearchResults(selected.tracks, query)
                TrackCollection(
                    tracks = shownTracks,
                    currentId = current?.id,
                    viewMode = controls.viewMode,
                    columns = controls.columns,
                    topPadding = BarHeight,
                    emptyText = "Sin resultados.",
                    onTrackMenu = onTrackMenu,
                    onPlay = { index -> vm.playQueue(shownTracks, index) }
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ CARPETAS (fisicas + virtuales)

@Composable
fun FoldersScreen(
    vm: BeroViewModel,
    onTrackMenu: (Track) -> Unit,
    onFolderTrackMenu: (Track, String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val controls = rememberGridControls(vm)
    val root = vm.storageRoot

    var rawPath by rememberSaveable { mutableStateOf(root) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }

    val bar = rememberLibraryBarState()
    val query = rememberSearchQuery(bar)

    // Defensa extra: si la ruta sale de la raiz o es una carpeta bloqueada, se vuelve a la raiz.
    val path = if (!rawPath.startsWith(root) || FolderGuard.isBlockedPath(rawPath)) root else rawPath
    val atRoot = path == root
    val openPlaylist = playlists.firstOrNull { it.id == openId }

    BackHandler(enabled = openPlaylist != null) { openId = null }
    BackHandler(enabled = openPlaylist == null && !atRoot) { rawPath = path.substringBeforeLast('/') }

    val canGoBack = openPlaylist != null || !atRoot
    val goBack: () -> Unit = {
        if (openPlaylist != null) openId = null else rawPath = path.substringBeforeLast('/')
        bar.closeSearch()
    }

    LibraryScaffold(
        title = openPlaylist?.name ?: if (atRoot) "Carpetas" else path.substringAfterLast('/'),
        subtitle = when {
            openPlaylist != null -> "Carpeta propia · ${openPlaylist.trackIds.size} pistas"
            atRoot -> "Memoria interna y carpetas propias"
            else -> path.removePrefix(root)
        },
        state = bar,
        onOpenSettings = onOpenSettings,
        onBack = if (canGoBack) goBack else null,
        gridControls = if (openPlaylist != null) controls else null
    ) {
        if (openPlaylist != null) {
            // ---------- Carpeta virtual abierta
            val folderBase by produceState(emptyList<Track>(), openPlaylist, tracks) {
                value = withContext(Dispatchers.Default) {
                    val byId = tracks.associateBy { it.id }
                    openPlaylist.trackIds.mapNotNull { byId[it] }
                }
            }
            val folderTracks = rememberSearchResults(folderBase, query)
            TrackCollection(
                tracks = folderTracks,
                currentId = current?.id,
                viewMode = controls.viewMode,
                columns = controls.columns,
                topPadding = BarHeight,
                header = {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BeroChip("Renombrar", selected = false, onClick = { renameId = openPlaylist.id })
                        BeroChip("Eliminar carpeta", selected = false, onClick = {
                            vm.deleteFolder(openPlaylist.id)
                            openId = null
                        })
                    }
                },
                emptyText = if (query.isNotEmpty()) "Sin resultados."
                else "Carpeta vacía. Mantén pulsada una canción y elige \"Añadir a carpeta\".",
                onTrackMenu = { track -> onFolderTrackMenu(track, openPlaylist.id) },
                onPlay = { index -> vm.playQueue(folderTracks, index) }
            )
        } else {
            // ---------- Carpetas del sistema (+ las propias en la raiz)
            val prefix = "$path/"
            val listing by produceState<FolderListing?>(null, tracks, path) {
                value = computeFolderListing(tracks, prefix)
            }
            val here = rememberSearchResults(listing?.here ?: emptyList(), query)
            val subfolders = remember(listing, query) {
                listing?.folders.orEmpty().filter { matchesQuery(it.first, query) }
            }
            val shownPlaylists = remember(playlists, query) {
                if (query.isEmpty()) playlists else playlists.filter { matchesQuery(it.name, query) }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = BarHeight, bottom = 16.dp)
            ) {
                if (atRoot) {
                    item(key = "label_mine") { FolderSectionLabel("Mis carpetas") }
                    item(key = "new_folder") {
                        FolderRow(
                            icon = Icons.Rounded.CreateNewFolder,
                            name = "Nueva carpeta",
                            subtitle = "Crea tu propia carpeta (playlist local)",
                            modifier = Modifier.animateItem()
                        ) { showCreate = true }
                    }
                    items(shownPlaylists, key = { "pl:${it.id}" }, contentType = { "playlist" }) { pl ->
                        FolderRow(
                            icon = Icons.Rounded.LibraryMusic,
                            name = pl.name,
                            subtitle = "${pl.trackIds.size} pistas",
                            modifier = Modifier.animateItem()
                        ) { openId = pl.id }
                    }
                    item(key = "label_storage") { FolderSectionLabel("Almacenamiento") }
                }

                if (listing != null && subfolders.isEmpty() && here.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (query.isNotEmpty()) "Sin resultados" else "No hay audios en esta carpeta",
                            modifier = Modifier.padding(32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(subfolders, key = { "folder:${it.first}" }, contentType = { "folder" }) { (name, count) ->
                    FolderRow(
                        icon = Icons.Rounded.Folder,
                        name = name,
                        subtitle = "$count pistas",
                        modifier = Modifier.animateItem()
                    ) {
                        rawPath = "$prefix$name"
                        bar.closeSearch()
                    }
                }
                itemsIndexed(here, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { index, t ->
                    TrackRow(
                        track = t,
                        isCurrent = t.id == current?.id,
                        modifier = Modifier
                            .animateItem()
                            .staggeredAppear(index),
                        onLongClick = { onTrackMenu(t) }
                    ) { vm.playQueue(here, index) }
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "Nueva carpeta",
            initial = "",
            confirmLabel = "Crear",
            onConfirm = { name ->
                vm.createFolder(name)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }
    renameId?.let { id ->
        NameDialog(
            title = "Renombrar carpeta",
            initial = playlists.firstOrNull { it.id == id }?.name.orEmpty(),
            confirmLabel = "Guardar",
            onConfirm = { name ->
                vm.renameFolder(id, name)
                renameId = null
            },
            onDismiss = { renameId = null }
        )
    }
}

@Composable
private fun FolderSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun FolderRow(
    icon: ImageVector,
    name: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .pressable(shape, 0.97f, onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
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
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ------------------------------------------------------------------------ FAVORITOS

@Composable
fun FavoritesScreen(vm: BeroViewModel, onTrackMenu: (Track) -> Unit, onOpenSettings: () -> Unit) {
    val favs by vm.favoriteTracks.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val controls = rememberGridControls(vm)

    val bar = rememberLibraryBarState()
    val query = rememberSearchQuery(bar)
    val shown = rememberSearchResults(favs, query)

    LibraryScaffold(
        "Favoritos",
        if (query.isNotEmpty()) "${shown.size} de ${favs.size} canciones" else "${favs.size} canciones",
        bar,
        onOpenSettings,
        gridControls = controls
    ) {
        TrackCollection(
            tracks = shown,
            currentId = current?.id,
            viewMode = controls.viewMode,
            columns = controls.columns,
            topPadding = BarHeight,
            emptyText = if (query.isNotEmpty()) "Sin resultados."
            else "Aún no tienes favoritos. Toca el corazón en el reproductor.",
            onTrackMenu = onTrackMenu,
            onPlay = { index -> vm.playQueue(shown, index) }
        )
    }
}

/** Contenido de una carpeta fisica: subcarpetas (con su numero de pistas) y las pistas que contiene directamente. */
private class FolderListing(val folders: List<Pair<String, Int>>, val here: List<Track>)

/** Recorre TODA la biblioteca: va en Dispatchers.Default y es cancelable (cada 256 pistas comprueba cancelacion). */
private suspend fun computeFolderListing(tracks: List<Track>, prefix: String): FolderListing =
    withContext(Dispatchers.Default) {
        val counts = java.util.TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
        val direct = ArrayList<Track>()
        var n = 0
        for (t in tracks) {
            if ((++n and 0xFF) == 0) ensureActive()
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
        FolderListing(counts.entries.map { it.key to it.value }, direct)
    }
