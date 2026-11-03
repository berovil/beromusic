package com.example.bero.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import com.example.bero.data.UnisonClient
import com.example.bero.data.MatchQuality
import com.example.bero.data.Track
import com.example.bero.data.TtmlCandidate
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.theme.beroWeight

/**
 * Buscador de letras TTML (fuente unica: Unison). Muestra los candidatos y deja elegir; nada se descarga
 * hasta que el usuario toca un resultado. La busqueda de AMLL no trae el TTML, asi que el nivel de sincronizacion
 * y el idioma solo se conocen tras descargar (se informa al terminar).
 */
@Composable
fun TtmlSearchDialog(vm: BeroViewModel, track: Track, onDismiss: () -> Unit) {
    val outcome by produceState<BeroViewModel.TtmlSearchOutcome?>(initialValue = null, track.id) {
        value = vm.searchTtml(track)
    }
    var downloading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !downloading) { Text("Cerrar") } },
        title = { Text("Letras TTML", fontWeight = beroWeight(FontWeight.Bold)) },
        text = {
            Column {
                Text(
                    "${track.title} · ${track.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                when (val o = outcome) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("  Buscando en ${UnisonClient.SOURCE_NAME}…")
                    }

                    BeroViewModel.TtmlSearchOutcome.NotFound ->
                        Text("No hay letras TTML para esta canción en ${UnisonClient.SOURCE_NAME}.")

                    is BeroViewModel.TtmlSearchOutcome.Error -> Text(o.message)

                    is BeroViewModel.TtmlSearchOutcome.Found -> Column {
                        Text(
                            "Elige la grabación correcta. Solo se muestran letras en formato TTML.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(o.candidates, key = { it.song.id }) { c ->
                                CandidateRow(c, enabled = !downloading) {
                                    downloading = true
                                    vm.downloadTtml(track, c.song) { ok ->
                                        downloading = false
                                        if (ok) onDismiss()
                                    }
                                }
                            }
                        }
                    }
                }
                if (downloading) {
                    Spacer(Modifier.height(8.dp))
                    Text("Descargando…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

@Composable
private fun CandidateRow(c: TtmlCandidate, enabled: Boolean, onClick: () -> Unit) {
    val quality = when (c.quality) {
        MatchQuality.HIGH -> "Coincidencia alta"
        MatchQuality.POSSIBLE -> "Posible coincidencia"
        MatchQuality.LOW -> "Coincidencia baja"
    }
    val sync = when (c.song.syncType.lowercase()) {
        "richsync" -> "Sincronizada por sílaba/palabra"
        "linesync" -> "Sincronizada por línea"
        else -> "Sincronización no indicada"
    }
    val confidence = when (c.song.confidence) {
        "high" -> "confianza alta"
        "medium" -> "confianza media"
        "low" -> "confianza baja"
        else -> null
    }
    val details = listOfNotNull(
        sync,
        c.song.language?.let { "idioma: $it" },
        confidence,
        c.song.durationSec?.let { "%d:%02d".format(it / 60, it % 60) }
    ).joinToString(" · ")
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Column {
            Text(c.song.title.ifEmpty { "—" }, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(c.song.artist, c.song.album).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$quality · TTML · ${UnisonClient.SOURCE_NAME}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.versionMismatch) {
                Text(
                    "Posible versión distinta (live, remix, acústica, duración distinta…)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
