package com.example.bero.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.bero.data.SpicyTrackCandidate
import com.example.bero.data.Track
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.theme.beroWeight

@Composable
fun SpicyLyricsSearchDialog(vm: BeroViewModel, track: Track, onDismiss: () -> Unit) {
    val outcome by produceState<BeroViewModel.SpicyLyricsSearchOutcome?>(
        initialValue = null,
        key1 = track.id,
    ) { value = vm.searchSpicyLyrics(track) }
    var fetching by remember(track.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!fetching) onDismiss() },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !fetching) { Text("Cerrar") } },
        title = { Text("Letras de Spicy Lyrics", fontWeight = beroWeight(FontWeight.Bold)) },
        text = {
            Column {
                Text(
                    "${track.title} · ${track.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                when (val result = outcome) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("  Buscando coincidencias…")
                    }
                    BeroViewModel.SpicyLyricsSearchOutcome.NotConfigured -> Text(
                        "Spicy Lyrics no está configurado en esta compilación. Se necesita una clave publicable sl_pk_ " +
                            "y habilitar el acceso para clientes nativos en el panel del servicio. No introduzcas una clave secreta aquí.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    BeroViewModel.SpicyLyricsSearchOutcome.NotFound -> Text(
                        "No se encontraron canciones que coincidan con estos datos.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is BeroViewModel.SpicyLyricsSearchOutcome.Error -> Text(
                        result.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is BeroViewModel.SpicyLyricsSearchOutcome.Found -> {
                        Text(
                            "Selecciona la grabación correcta. La búsqueda no confirma si hay letras hasta consultar cada resultado.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            Modifier.heightIn(max = 390.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(result.candidates, key = { it.id }) { candidate ->
                                CandidateRow(candidate, enabled = !fetching) {
                                    fetching = true
                                    vm.useSpicyLyrics(track, candidate) { success ->
                                        fetching = false
                                        if (success) onDismiss()
                                    }
                                }
                            }
                        }
                    }
                }
                if (fetching) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Preparando la letra…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
    )
}

@Composable
private fun CandidateRow(candidate: SpicyTrackCandidate, enabled: Boolean, onClick: () -> Unit) {
    val tierLabel = when (candidate.tier.lowercase()) {
        "exact" -> "Coincidencia exacta"
        "likely" -> "Coincidencia probable"
        "version" -> "Otra versión"
        "other_artist" -> "Otro artista"
        else -> "Coincidencia relacionada"
    }
    val details = listOfNotNull(
        candidate.album.takeIf { it.isNotBlank() },
        candidate.durationSec?.let { seconds ->
            val total = seconds.toInt().coerceAtLeast(0)
            "%d:%02d".format(total / 60, total % 60)
        },
        if (candidate.isVariant) "Otra publicación" else null,
    ).joinToString(" · ")

    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(candidate.title.ifBlank { "Canción sin título" }, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(candidate.artist.ifBlank { "Artista desconocido" }, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tierLabel, fontSize = 12.sp,
            color = if (candidate.tier.equals("exact", true)) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant)
        if (details.isNotBlank()) Text(details, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!candidate.tier.equals("exact", true)) Text(
            "Comprueba la versión: su sincronización puede no coincidir con tu archivo.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
        )
    }
}
