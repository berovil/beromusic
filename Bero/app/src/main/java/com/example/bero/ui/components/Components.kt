package com.example.bero.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bero.R
import com.example.bero.data.ArtworkLoader
import com.example.bero.data.Track
import kotlinx.coroutines.delay

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Animacion tactil estilo Pixel: al presionar se reduce con rebote suave y se ilumina con el color de acento.
 * Usa un InteractionSource propio (sin ripple) y NO consume gestos fuera de su area: no hay clics fantasma.
 */
fun Modifier.pressable(
    shape: Shape = RoundedCornerShape(20.dp),
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = longPressable(shape, pressedScale, null, onClick)

/** Igual que [pressable] pero con pulsacion larga (Modifier.combinedClickable + vibracion). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.longPressable(
    shape: Shape = RoundedCornerShape(20.dp),
    pressedScale: Float = 0.96f,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 0.24f else 0f,
        label = "pressGlow"
    )
    val accent = MaterialTheme.colorScheme.primary
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .drawBehind { drawRect(accent.copy(alpha = glow)) }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
            onLongClick = if (onLongClick == null) null else ({
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            })
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BeroIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    active: Boolean = false,
    tint: Color? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    onLongClick: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "iconScale"
    )
    val background by animateColorAsState(
        targetValue = when {
            pressed -> accent.copy(alpha = 0.38f)
            active -> accent.copy(alpha = 0.20f)
            else -> Color.Transparent
        },
        label = "iconBg"
    )
    val iconColor by animateColorAsState(
        targetValue = if (pressed || active) accent else (tint ?: LocalContentColor.current),
        label = "iconTint"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = if (onLongClick == null) null else ({
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                })
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = iconColor, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun BigPlayButton(
    icon: ImageVector,
    contentDescription: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bigScale"
    )
    val bg by animateColorAsState(
        targetValue = if (pressed) lerp(accent, Color.White, 0.35f) else accent,
        label = "bigBg"
    )
    val luminance = (0.299f * accent.red + 0.587f * accent.green + 0.114f * accent.blue)
    val onAccent = if (luminance > 0.5f) Color(0xFF101014) else Color.White
    Box(
        modifier = modifier
            .size(88.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = icon, animationSpec = tween(180), label = "playPauseIcon") { current ->
            Icon(current, contentDescription, tint = onAccent, modifier = Modifier.size(52.dp))
        }
    }
}

@Composable
fun BeroChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape
            )
            .pressable(shape, 0.93f, onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun BeroButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary)
            .pressable(shape, 0.95f, onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
            .padding(16.dp),
        content = content
    )
}

/** Marca de Bero (B-nota + carita :D) sobre un degradado del color de acento. */
@Composable
fun BeroLogo(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(lerp(accent, Color.White, 0.15f), lerp(accent, Color.Black, 0.55f)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bero_mark),
            contentDescription = "Bero",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(0.9f)
        )
    }
}

/** Caratula real del archivo (MediaMetadataRetriever); si no hay, muestra el logo de Bero. */
@Composable
fun Artwork(
    track: Track?,
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    px: Int = 512,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = track?.let { ArtworkLoader.peek(it, px) }?.asImageBitmap(),
        track?.id,
        px
    ) {
        val cached = track?.let { ArtworkLoader.peek(it, px) }
        value = cached?.asImageBitmap()
        if (cached == null && track != null) {
            value = ArtworkLoader.load(context, track, px)?.asImageBitmap()
        }
    }
    Box(modifier.clip(RoundedCornerShape(corner))) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            BeroLogo(Modifier.fillMaxSize())
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            BeroIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás", onBack)
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onSettings != null) {
            BeroIconButton(Icons.Rounded.Settings, "Ajustes", onSettings)
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .longPressable(shape, 0.97f, onLongClick, onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(track, Modifier.size(52.dp), corner = 14.dp, px = 160)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatTime(track.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TrackList(
    tracks: List<Track>,
    currentId: Long?,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    emptyText: String = "No hay canciones",
    onTrackMenu: ((Track) -> Unit)? = null,
    onPlay: (Int) -> Unit,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (header != null) {
            item(key = "header") { header() }
        }
        if (tracks.isEmpty()) {
            item(key = "empty") {
                Text(
                    emptyText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { index, t ->
            TrackRow(
                track = t,
                isCurrent = t.id == currentId,
                modifier = Modifier
                    .animateItem()
                    .staggeredAppear(index),
                onLongClick = onTrackMenu?.let { menu -> { menu(t) } }
            ) { onPlay(index) }
        }
    }
}


/**
 * Entrada suave (fundido + ligero desplazamiento) escalonada para los primeros elementos de una lista/cuadricula.
 * Pasado [maxStaggered] no hace nada (cero coste al hacer scroll).
 */
fun Modifier.staggeredAppear(index: Int, maxStaggered: Int = 10): Modifier =
    if (index >= maxStaggered) {
        this
    } else {
        composed {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(index * 35L)
                progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            }
            graphicsLayer {
                alpha = progress.value
                translationY = (1f - progress.value) * 24.dp.toPx()
            }
        }
    }
