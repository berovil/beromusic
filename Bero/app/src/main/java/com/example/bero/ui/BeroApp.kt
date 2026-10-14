package com.example.bero.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.DeleteOutcome
import com.example.bero.data.MusicRepository
import com.example.bero.data.Track
import com.example.bero.ui.components.Artwork
import com.example.bero.ui.components.BeroButton
import com.example.bero.ui.components.BeroIconButton
import com.example.bero.ui.components.pressable
import com.example.bero.ui.screens.AlbumsScreen
import com.example.bero.ui.screens.DeleteConfirmDialog
import com.example.bero.ui.screens.FavoritesScreen
import com.example.bero.ui.screens.FoldersScreen
import com.example.bero.ui.screens.LrcEditorScreen
import com.example.bero.ui.screens.PlayerScreen
import com.example.bero.ui.screens.SettingsScreen
import com.example.bero.ui.screens.TrackInfoDialog
import com.example.bero.ui.screens.TrackMenuSheet
import com.example.bero.ui.screens.TracksScreen

enum class MainTab(val label: String, val icon: ImageVector) {
    Tracks("Pistas", Icons.Rounded.QueueMusic),
    Albums("Álbumes", Icons.Rounded.Album),
    Folders("Carpetas", Icons.Rounded.Folder),
    Favorites("Favoritos", Icons.Rounded.Favorite),
}

/** Punto de entrada de la UI: gestiona permisos y muestra la app cuando hay acceso al audio. */
@Composable
fun BeroApp(vm: BeroViewModel) {
    val ctx = LocalContext.current
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    var hasAudio by remember { mutableStateOf(has(audioPerm)) }
    var hasMic by remember { mutableStateOf(has(Manifest.permission.RECORD_AUDIO)) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMic = has(Manifest.permission.RECORD_AUDIO)
        val granted = has(audioPerm)
        if (granted && !hasAudio) vm.loadLibrary()
        hasAudio = granted
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            val missing = buildList {
                if (!has(audioPerm)) add(audioPerm)
                if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (missing.isNotEmpty()) multiLauncher.launch(missing.toTypedArray())
        }
    }

    if (!hasAudio) {
        PermissionScreen(
            onGrant = { multiLauncher.launch(arrayOf(audioPerm)) },
            onOpenSettings = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                )
            }
        )
    } else {
        LibraryApp(
            vm = vm,
            hasMic = hasMic,
            onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        )
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Bero necesita acceso a tu música", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Concede el permiso de audio para listar tus canciones. " +
                "El micrófono solo se usa para animar el espectrograma; no se graba nada.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        BeroButton("Conceder acceso", onGrant)
        Spacer(Modifier.height(12.dp))
        BeroButton("Abrir ajustes de la app", onOpenSettings)
    }
}

private class PendingDelete(val track: Track, val retryDelete: Boolean)

@Composable
private fun LibraryApp(vm: BeroViewModel, hasMic: Boolean, onRequestMic: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(MainTab.Tracks) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Menu contextual (pulsacion larga) y sus acciones
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    var deleteTrack by remember { mutableStateOf<Track?>(null) }
    var editorTrack by remember { mutableStateOf<Track?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    val expanded by vm.playerExpanded.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()

    // Mensajes de una sola vez y enlaces externos (Spotify) emitidos por el ViewModel
    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) {
        vm.openUrl.collect { uri ->
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: Exception) {
                Toast.makeText(ctx, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Borrado fisico: MediaStore.createDeleteRequest (Android 11+) o RecoverableSecurityException (Android 10)
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingDelete
        pendingDelete = null
        if (pending != null && result.resultCode == Activity.RESULT_OK) {
            val ok = if (pending.retryDelete) MusicRepository.retryDelete(ctx, pending.track) else true
            if (ok) vm.onTrackDeleted(pending.track) else vm.showMessage("No se pudo eliminar el archivo")
        }
    }

    fun startDelete(track: Track) {
        when (val outcome = MusicRepository.requestDelete(ctx, track)) {
            is DeleteOutcome.Done -> vm.onTrackDeleted(track)
            is DeleteOutcome.NeedsConfirmation -> {
                pendingDelete = PendingDelete(track, outcome.retryDelete)
                deleteLauncher.launch(IntentSenderRequest.Builder(outcome.sender).build())
            }
            is DeleteOutcome.Failed -> vm.showMessage("No se pudo eliminar el archivo")
        }
    }

    // Orden de registro: el ultimo en componerse tiene prioridad (jugador > editor > ajustes).
    BackHandler(enabled = showSettings && !expanded) { showSettings = false }
    BackHandler(enabled = editorOpen && !expanded) { editorOpen = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    current?.let { track ->
                        MiniPlayer(
                            track = track,
                            isPlaying = isPlaying,
                            onOpen = vm::expandPlayer,
                            onToggle = vm::togglePlay,
                            onNext = vm::next
                        )
                    }
                    BeroBottomBar(selected = tab, onSelect = { tab = it })
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                val openMenu: (Track) -> Unit = { menuTrack = it }
                when (tab) {
                    MainTab.Tracks -> TracksScreen(vm, onTrackMenu = openMenu) { showSettings = true }
                    MainTab.Albums -> AlbumsScreen(vm, onTrackMenu = openMenu) { showSettings = true }
                    MainTab.Folders -> FoldersScreen(vm, onTrackMenu = openMenu) { showSettings = true }
                    MainTab.Favorites -> FavoritesScreen(vm, onTrackMenu = openMenu) { showSettings = true }
                }
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            SettingsScreen(vm, onBack = { showSettings = false })
        }

        AnimatedVisibility(
            visible = editorOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            editorTrack?.let { LrcEditorScreen(vm, it, onClose = { editorOpen = false }) }
        }

        AnimatedVisibility(
            visible = expanded && current != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            PlayerScreen(vm, hasMic = hasMic, onRequestMic = onRequestMic)
        }

        menuTrack?.let { t ->
            TrackMenuSheet(
                track = t,
                onDismiss = { menuTrack = null },
                onInfo = {
                    menuTrack = null
                    infoTrack = t
                },
                onEditLrc = {
                    menuTrack = null
                    editorTrack = t
                    editorOpen = true
                },
                onShare = {
                    menuTrack = null
                    shareTrack(ctx, t)
                },
                onDelete = {
                    menuTrack = null
                    deleteTrack = t
                }
            )
        }
        infoTrack?.let { t -> TrackInfoDialog(vm, t) { infoTrack = null } }
        deleteTrack?.let { t ->
            DeleteConfirmDialog(
                track = t,
                onConfirm = {
                    deleteTrack = null
                    startDelete(t)
                },
                onDismiss = { deleteTrack = null }
            )
        }
    }
}

/** Comparte el archivo de audio fisico con Intent.ACTION_SEND (permiso de lectura temporal sobre la URI). */
private fun shareTrack(ctx: Context, track: Track) {
    try {
        val mime = ctx.contentResolver.getType(track.uri) ?: "audio/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, track.uri)
            putExtra(Intent.EXTRA_SUBJECT, track.title)
            clipData = ClipData.newRawUri(track.title, track.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Compartir ${track.title}"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No se pudo compartir el archivo", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
            .pressable(shape, 0.98f, onOpen)
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(track, Modifier.size(46.dp), corner = 16.dp, px = 160)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                track.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BeroIconButton(
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
            onClick = onToggle,
            size = 48.dp,
            iconSize = 28.dp
        )
        BeroIconButton(Icons.Rounded.SkipNext, "Siguiente", onNext, size = 48.dp, iconSize = 28.dp)
    }
}

@Composable
private fun BeroBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    val barShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        MainTab.entries.forEach { item ->
            val isSelected = item == selected
            Column(
                Modifier
                    .weight(1f)
                    .pressable(RoundedCornerShape(20.dp), 0.92f) { onSelect(item) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(width = 58.dp, height = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    item.label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
