package com.example.bero.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.example.bero.audio.AudioFx
import com.example.bero.data.ShuffleMode
import com.example.bero.data.Track
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.Artwork
import com.example.bero.ui.components.BeroIconButton
import com.example.bero.ui.components.BigPlayButton
import com.example.bero.ui.components.SpectrumVisualizer
import com.example.bero.ui.components.VisualizerStyleDialog
import com.example.bero.ui.components.formatTime
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.example.bero.ui.theme.beroShape
import com.example.bero.ui.theme.beroWeight

/**
 * PlayerScreen SOLO decide el modo y el fondo. Cada bloque (barra superior, caratula, espectro,
 * slider, controles) es un composable independiente que lee unicamente el estado que necesita,
 * asi que un cambio de posicion, de reproduccion o de favorito recompone solo su pieza.
 */
@Composable
fun PlayerScreen(vm: BeroViewModel, hasMic: Boolean, onRequestMic: () -> Unit) {
    val track by vm.currentTrack.collectAsStateWithLifecycle()
    val showLyrics by vm.showLyrics.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }

    val primary = MaterialTheme.colorScheme.primary
    val accent = remember(primary) { lerp(primary, Color.White, 0.25f) }
    val background = remember(primary) {
        val base = Color(0xFF0B0B10)
        Brush.verticalGradient(listOf(lerp(base, primary, 0.50f), lerp(base, primary, 0.15f), base))
    }

    // Atras: primero cierra la vista de letras, luego minimiza.
    BackHandler { if (showLyrics) vm.toggleLyrics() else vm.collapsePlayer() }

    val current = track
    if (current == null) {
        LaunchedEffect(Unit) { vm.collapsePlayer() }
        return
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            Modifier
                .fillMaxSize()
                .background(background)
                // Absorbe cualquier toque que no consuma un hijo: nada llega a las pantallas de atras.
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Crossfade(targetState = showLyrics, animationSpec = tween(220), label = "playerMode") { lyricsMode ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    if (lyricsMode) {
                        LyricsModeContent(vm, current, accent)
                    } else {
                        NormalModeContent(vm, current, accent, hasMic, onRequestMic) { showQueue = true }
                    }
                }
            }
        }
        if (showQueue) QueueSheet(vm, accent) { showQueue = false }
    }
}

// ============================================================================ MODO NORMAL

@Composable
private fun ColumnScope.NormalModeContent(
    vm: BeroViewModel,
    current: Track,
    accent: Color,
    hasMic: Boolean,
    onRequestMic: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    // Color del tema, aclarado hasta contrastar con el fondo oscuro del reproductor.
    val spectrumColor = remember(accent) {
        var c = accent
        var i = 0
        while (c.luminance() < 0.55f && i < 10) {
            c = lerp(c, Color.White, 0.12f)
            i++
        }
        c
    }

    PlayerTopBar(
        vm = vm,
        title = "NOW PLAYING",
        subtitle = current.album,
        spaced = true,
        icon = Icons.Rounded.QueueMusic,
        iconDescription = "Fila de reproducción",
        iconActive = false,
        accent = accent,
        onIcon = onOpenQueue
    )

    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        ArtworkSwipe(vm, current, onSwipeUp = onOpenQueue)
    }

    val panelShape = beroShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .clip(panelShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), panelShape)
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)
    ) {
        PanelTopRow(vm, accent, onOpenQueue)
        SpectrumSection(vm, hasMic, onRequestMic, spectrumColor)
        Text(
            current.title,
            fontSize = 26.sp,
            fontWeight = beroWeight(FontWeight.ExtraBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            current.artist,
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.70f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        PlayerSeekBar(vm, current.durationMs, accent)
        MainControls(vm, accent)
        SecondaryControls(vm, current, accent)
    }
}

// ============================================================================ MODO LETRAS (minimalista)

/** Solo: barra superior minima, letras interactivas y, abajo, slider + anterior/play/siguiente. */
@Composable
private fun ColumnScope.LyricsModeContent(vm: BeroViewModel, current: Track, accent: Color) {
    PlayerTopBar(
        vm = vm,
        title = current.title,
        subtitle = current.artist,
        spaced = false,
        icon = Icons.Rounded.Lyrics,
        iconDescription = "Cerrar letras",
        iconActive = true,
        accent = accent,
        onIcon = { vm.toggleLyrics() }
    )

    LyricsSection(
        vm,
        accent,
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp)
    ) {
        PlayerSeekBar(vm, current.durationMs, accent)
        MainControls(vm, accent)
    }
}

@Composable
private fun LyricsSection(vm: BeroViewModel, accent: Color, modifier: Modifier) {
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    LyricsPanel(vm, lyrics, accent, modifier)
}

// ============================================================================ PIEZAS AISLADAS

@Composable
private fun swipeDownToCollapse(onCollapse: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onCollapse)
    return Modifier.pointerInput(Unit) {
        val threshold = 60.dp.toPx()
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total > threshold) latest()
                total = 0f
            },
            onDragCancel = { total = 0f },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                total += dragAmount
            }
        )
    }
}

/** Barra superior: deslizar hacia abajo sobre ELLA minimiza el reproductor. */
@Composable
private fun PlayerTopBar(
    vm: BeroViewModel,
    title: String,
    subtitle: String,
    spaced: Boolean,
    icon: ImageVector,
    iconDescription: String,
    iconActive: Boolean,
    accent: Color,
    onIcon: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 8.dp)
            .then(swipeDownToCollapse { vm.collapsePlayer() }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BeroIconButton(
            icon = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Minimizar",
            onClick = vm::collapsePlayer,
            accent = accent,
            tint = Color.White
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                fontSize = if (spaced) 12.sp else 16.sp,
                fontWeight = beroWeight(FontWeight.Bold),
                letterSpacing = if (spaced) 2.sp else 0.sp,
                color = Color.White.copy(alpha = if (spaced) 0.85f else 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.55f)
            )
        }
        BeroIconButton(
            icon = icon,
            contentDescription = iconDescription,
            onClick = onIcon,
            active = iconActive,
            accent = accent,
            tint = Color.White
        )
    }
}

/**
 * Gestos SOLO sobre la caratula: izquierda/derecha = siguiente/anterior; deslizar ARRIBA = revelar la fila.
 * Un unico detector con bloqueo de eje evita gestos cruzados.
 */
@Composable
private fun ArtworkSwipe(vm: BeroViewModel, track: Track, onSwipeUp: () -> Unit) {
    val latestSwipeUp by rememberUpdatedState(onSwipeUp)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()

        Box(
            Modifier
                .size(side)
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value
                    rotationZ = offsetX.value / 60f
                }
                .pointerInput(Unit) {
                    val hThreshold = 90.dp.toPx()
                    val vThreshold = 80.dp.toPx()
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(
                        onDragStart = {
                            totalX = 0f
                            totalY = 0f
                        },
                        onDragEnd = {
                            val tx = totalX
                            val ty = totalY
                            totalX = 0f
                            totalY = 0f
                            scope.launch { offsetX.animateTo(0f, spring()) }
                            scope.launch { offsetY.animateTo(0f, spring()) }
                            if (abs(tx) > abs(ty)) {
                                if (tx <= -hThreshold) vm.next()
                                else if (tx >= hThreshold) vm.previous(forceTrack = true)
                            } else if (ty <= -vThreshold) {
                                latestSwipeUp()
                            }
                        },
                        onDragCancel = {
                            totalX = 0f
                            totalY = 0f
                            scope.launch { offsetX.animateTo(0f, spring()) }
                            scope.launch { offsetY.animateTo(0f, spring()) }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalX += dragAmount.x
                            totalY += dragAmount.y
                            if (abs(totalX) > abs(totalY)) {
                                scope.launch { offsetX.snapTo(offsetX.value + dragAmount.x * 0.7f) }
                            } else if (totalY < 0f) {
                                scope.launch { offsetY.snapTo((offsetY.value + dragAmount.y * 0.4f).coerceAtMost(0f)) }
                            }
                        }
                    )
                }
        ) {
            // Al cambiar de cancion la caratula entra con fundido + ligero zoom.
            AnimatedContent(
                targetState = track,
                contentKey = { it.id },
                transitionSpec = {
                    (fadeIn(tween(300)) + scaleIn(initialScale = 0.92f, animationSpec = tween(300))) togetherWith
                        fadeOut(tween(180))
                },
                label = "artwork"
            ) { t ->
                Artwork(track = t, modifier = Modifier.fillMaxSize(), corner = 32.dp, px = 1024)
            }
        }
    }
}

/**
 * Fila superior del panel de controles:
 *  - IZQUIERDA: boton para ENCENDER/APAGAR el espectrograma al instante (apagado = el Visualizer ni se crea);
 *  - CENTRO: asa; deslizar hacia arriba (o tocar) abre la fila de reproduccion;
 *  - DERECHA: estilo del espectrograma (solo si esta encendido).
 */
@Composable
private fun PanelTopRow(vm: BeroViewModel, accent: Color, onOpenQueue: () -> Unit) {
    val enabled by vm.spectrogramEnabled.collectAsStateWithLifecycle()
    val style by vm.settings.visualizerStyle.collectAsStateWithLifecycle()
    var showStyles by remember { mutableStateOf(false) }
    val latestOpen by rememberUpdatedState(onOpenQueue)
    val dim = Color.White.copy(alpha = 0.75f)

    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BeroIconButton(
            icon = Icons.Rounded.GraphicEq,
            contentDescription = if (enabled) "Apagar espectrograma" else "Encender espectrograma",
            onClick = vm::toggleSpectrogram,
            size = 34.dp,
            iconSize = 20.dp,
            active = enabled,
            accent = accent,
            tint = dim
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) { detectTapGestures { latestOpen() } }
                .pointerInput(Unit) {
                    val threshold = 28.dp.toPx()
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -threshold) latestOpen()
                            total = 0f
                        },
                        onDragCancel = { total = 0f },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            total += dy
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 38.dp, height = 4.dp)
                    .clip(beroShape(2.dp))
                    .background(Color.White.copy(alpha = 0.35f))
            )
        }
        if (enabled) {
            BeroIconButton(
                icon = Icons.Rounded.Tune,
                contentDescription = "Estilo del espectrograma",
                onClick = { showStyles = true },
                size = 34.dp,
                iconSize = 20.dp,
                accent = accent,
                tint = dim
            )
        } else {
            Spacer(Modifier.size(34.dp))
        }
    }

    if (showStyles) {
        VisualizerStyleDialog(
            current = style,
            onSelect = { vm.settings.setVisualizerStyle(it) },
            onDismiss = { showStyles = false }
        )
    }
}

/**
 * Espectro: si esta apagado, NO se compone => no se crea el Visualizer ni su hilo (cero consumo).
 * Entra/sale con una animacion suave y usa el color principal del tema.
 */
@Composable
private fun SpectrumSection(vm: BeroViewModel, hasMic: Boolean, onRequestMic: () -> Unit, color: Color) {
    val enabled by vm.spectrogramEnabled.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = enabled,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
        val sessionId by AudioFx.sessionId.collectAsStateWithLifecycle()
        val style by vm.settings.visualizerStyle.collectAsStateWithLifecycle()
        Column {
            SpectrumVisualizer(
                sessionId = sessionId,
                isPlaying = isPlaying,
                hasPermission = hasMic,
                style = style,
                color = color,
                onNeedPermission = onRequestMic,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Slider: unico composable que observa la posicion (4 Hz). La etiqueta de tiempo solo cambia 1 vez/segundo. */
@Composable
private fun PlayerSeekBar(vm: BeroViewModel, trackDurationMs: Long, accent: Color) {
    val positionState = vm.positionMs.collectAsStateWithLifecycle()
    val liveDuration by vm.durationMs.collectAsStateWithLifecycle()
    val duration = (if (liveDuration > 0) liveDuration else trackDurationMs).coerceAtLeast(1L)

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val fraction = if (dragging) dragFraction
    else (positionState.value.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.padding(top = 4.dp)) {
        Slider(
            value = fraction,
            onValueChange = {
                dragging = true
                dragFraction = it
            },
            onValueChangeFinished = {
                vm.seekTo((dragFraction * duration).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.20f)
            )
        )
        TimeRow(positionState, duration, dragging, dragFraction)
    }
}

@Composable
private fun TimeRow(position: State<Long>, durationMs: Long, dragging: Boolean, dragFraction: Float) {
    // derivedStateOf: este Row solo se recompone cuando cambia el SEGUNDO mostrado.
    val shownSecond by remember(dragging, dragFraction, durationMs) {
        derivedStateOf {
            if (dragging) (dragFraction * durationMs / 1000).toLong() else position.value / 1000
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatTime(shownSecond * 1000), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
        Text(formatTime(durationMs), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

/** Anterior, Play/Pause (muy grande) y Siguiente en la misma linea. */
@Composable
private fun MainControls(vm: BeroViewModel, accent: Color) {
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BeroIconButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = "Anterior",
            onClick = vm::skipPrevious,
            size = 68.dp,
            iconSize = 40.dp,
            accent = accent,
            tint = Color.White
        )
        BigPlayButton(
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
            accent = accent,
            onClick = vm::togglePlay
        )
        BeroIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "Siguiente",
            onClick = vm::next,
            size = 68.dp,
            iconSize = 40.dp,
            accent = accent,
            tint = Color.White
        )
    }
}

/**
 * Aleatorio (toque: OFF -> normal -> Smart; pulsacion larga: menu), repetir, letras, ecualizador y me gusta.
 */
@Composable
private fun SecondaryControls(vm: BeroViewModel, track: Track, accent: Color) {
    val shuffle by vm.shuffleMode.collectAsStateWithLifecycle()
    val repeat by vm.repeatMode.collectAsStateWithLifecycle()
    val eq by AudioFx.state.collectAsStateWithLifecycle()
    val favState = vm.favorites.ids.collectAsStateWithLifecycle()
    var showEq by remember { mutableStateOf(false) }
    var shuffleMenu by remember { mutableStateOf(false) }
    val dim = Color.White.copy(alpha = 0.8f)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            BeroIconButton(
                icon = if (shuffle == ShuffleMode.SMART) Icons.Rounded.AutoAwesome else Icons.Rounded.Shuffle,
                contentDescription = "Aleatorio",
                onClick = vm::cycleShuffle,
                onLongClick = { shuffleMenu = true },
                active = shuffle != ShuffleMode.OFF,
                accent = accent,
                tint = dim
            )
            DropdownMenu(expanded = shuffleMenu, onDismissRequest = { shuffleMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Aleatorio normal") },
                    onClick = {
                        vm.setShuffleMode(ShuffleMode.ON)
                        shuffleMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Smart Shuffle (Last.fm)") },
                    onClick = {
                        vm.setShuffleMode(ShuffleMode.SMART)
                        shuffleMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Desactivar aleatorio") },
                    onClick = {
                        vm.setShuffleMode(ShuffleMode.OFF)
                        shuffleMenu = false
                    }
                )
            }
        }
        BeroIconButton(
            icon = if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            contentDescription = "Repetir",
            onClick = vm::cycleRepeat,
            active = repeat != Player.REPEAT_MODE_OFF,
            accent = accent,
            tint = dim
        )
        BeroIconButton(
            icon = Icons.Rounded.Lyrics,
            contentDescription = "Letras",
            onClick = vm::toggleLyrics,
            accent = accent,
            tint = dim
        )
        BeroIconButton(
            icon = Icons.Rounded.Equalizer,
            contentDescription = "Ecualizador",
            onClick = { showEq = true },
            active = eq.available && eq.enabled,
            accent = accent,
            tint = dim
        )
        // derivedStateOf: este boton solo se recompone si CAMBIA el estado de ESTA cancion, no con cualquier favorito.
        val liked by remember(track.id) { derivedStateOf { track.id in favState.value } }
        BeroIconButton(
            icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = "Me gusta",
            onClick = { vm.toggleFavorite(track.id) },
            active = liked,
            accent = accent,
            tint = dim
        )
    }

    if (showEq) EqualizerDialog(onDismiss = { showEq = false })
}
