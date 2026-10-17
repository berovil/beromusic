package com.example.bero.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.lastfm.PreviewInfo
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.LastFmStatus
import com.example.bero.ui.Recommendation
import com.example.bero.ui.components.BeroButton
import com.example.bero.ui.components.BeroChip
import com.example.bero.ui.components.BeroIconButton
import com.example.bero.ui.components.GlassCard
import com.example.bero.ui.components.LibraryBarDefaults
import com.example.bero.ui.components.LibraryScaffold
import com.example.bero.ui.components.RemoteArtwork
import com.example.bero.ui.components.pressable
import com.example.bero.ui.components.rememberLibraryBarState
import com.example.bero.ui.components.staggeredAppear

/**
 * Descubrir: musica que NO tienes en el telefono, recomendada en linea segun tu gusto (Last.fm).
 * Cada tarjeta trae caratula, afinidad y una vista previa de 30 s; para oir la cancion completa se abre
 * en Deezer / YouTube / Last.fm (Last.fm no ofrece audio).
 */
@Composable
fun DiscoverScreen(vm: BeroViewModel, onOpenSettings: () -> Unit) {
    val recs by vm.recommendations.collectAsStateWithLifecycle()
    val status by vm.lastFmStatus.collectAsStateWithLifecycle()
    val message by vm.lastFmMessage.collectAsStateWithLifecycle()
    val apiKey by vm.settings.lastFmApiKey.collectAsStateWithLifecycle()
    val onlyNew by vm.settings.discoverNewOnly.collectAsStateWithLifecycle()
    val preview by vm.previewState.collectAsStateWithLifecycle()

    // Al salir de la pestana se libera el reproductor de vistas previas.
    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }
    LaunchedEffect(apiKey) { vm.loadRecommendations() }

    val bar = rememberLibraryBarState()
    LibraryScaffold(
        title = "Descubrir",
        subtitle = if (recs.isEmpty()) "Recomendaciones en línea según tu gusto" else "${recs.size} recomendaciones para ti",
        state = bar,
        onOpenSettings = onOpenSettings,
        searchEnabled = false
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LibraryBarDefaults.Height + 4.dp, bottom = 24.dp)
        ) {
            item(key = "intro") {
                IntroCard(
                    noKey = apiKey.isBlank(),
                    status = status,
                    message = message,
                    hasResults = recs.isNotEmpty(),
                    onlyNew = onlyNew,
                    onToggleOnlyNew = { vm.settings.setDiscoverNewOnly(!onlyNew) },
                    onRefresh = vm::refreshLastFm,
                    onOpenSettings = onOpenSettings
                )
            }
            items(recs, key = { it.suggestion.key }, contentType = { "rec" }) { rec ->
                val index = recs.indexOf(rec)
                RecommendationRow(
                    rec = rec,
                    vm = vm,
                    previewing = preview.key == rec.suggestion.key,
                    previewLoading = preview.key == rec.suggestion.key && preview.loading,
                    modifier = Modifier
                        .animateItem()
                        .staggeredAppear(index)
                )
            }
        }
    }
}

@Composable
private fun IntroCard(
    noKey: Boolean,
    status: LastFmStatus,
    message: String?,
    hasResults: Boolean,
    onlyNew: Boolean,
    onToggleOnlyNew: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    GlassCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize()
    ) {
        if (noKey) {
            Text("Recomendaciones en línea", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Para descubrir música nueva según tus gustos necesitas una API key gratuita de Last.fm " +
                    "(sin iniciar sesión ni pagar). Pégala en Ajustes → Smart Shuffle · Last.fm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            BeroButton("Ir a Ajustes", onClick = onOpenSettings)
            return@GlassCard
        }

        Text("Según tu gusto musical", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        when {
            status == LastFmStatus.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Analizando tus favoritas y tus artistas más frecuentes…", style = MaterialTheme.typography.bodySmall)
            }

            status == LastFmStatus.ERROR -> Text(
                "No se pudo cargar: ${message ?: "error desconocido"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            hasResults -> Text(
                "Canciones parecidas a las que tienes y artistas afines que aún no están en tu teléfono. " +
                    "Toca una para oír 30 s; con el icono de enlace la escuchas completa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> Text(
                "Todavía no hay recomendaciones. Marca algunas canciones como favoritas o pulsa Actualizar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BeroChip("Solo artistas nuevos", selected = onlyNew, onClick = onToggleOnlyNew)
            BeroChip("Actualizar", selected = false, onClick = onRefresh)
        }
    }
}

@Composable
private fun RecommendationRow(
    rec: Recommendation,
    vm: BeroViewModel,
    previewing: Boolean,
    previewLoading: Boolean,
    modifier: Modifier,
) {
    val s = rec.suggestion
    // Caratula + vista previa + enlace se piden de forma perezosa (solo para las filas visibles) y quedan en cache.
    val info by produceState<PreviewInfo?>(initialValue = null, s.key) { value = vm.lookupPreview(s) }
    val previewUrl = info?.previewUrl
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)

    val onPlay: () -> Unit = {
        when {
            previewUrl != null -> vm.togglePreview(s, previewUrl)
            info == null -> vm.showMessage("Buscando la vista previa…")
            else -> vm.showMessage("Esta canción no tiene vista previa; ábrela con el icono de enlace")
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .pressable(shape, 0.97f, onPlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteArtwork(info?.coverUrl, Modifier.size(60.dp), corner = 16.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (previewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                s.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Afinidad ${rec.affinity}% · " + if (rec.artistInLibrary) "artista que ya tienes" else "artista nuevo",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (previewing && previewLoading) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            BeroIconButton(
                icon = if (previewing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (previewing) "Detener vista previa" else "Escuchar 30 s",
                onClick = onPlay,
                active = previewing
            )
        }

        Box {
            BeroIconButton(
                icon = Icons.Rounded.OpenInNew,
                contentDescription = "Escuchar completa",
                onClick = { menu = true }
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                info?.link?.let { link ->
                    DropdownMenuItem(
                        text = { Text("Abrir en Deezer") },
                        onClick = {
                            menu = false
                            vm.openLink(link)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Buscar en YouTube") },
                    onClick = {
                        menu = false
                        vm.openYouTubeSearch(s)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ver en Last.fm") },
                    onClick = {
                        menu = false
                        vm.openLink(s.url)
                    }
                )
            }
        }
    }
}
