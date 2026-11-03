package com.example.bero.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bero.data.Track
import com.example.bero.data.TrackInfo
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.Artwork
import com.example.bero.ui.components.formatTime
import com.example.bero.ui.components.pressable
import java.text.DateFormat
import java.util.Date
import com.example.bero.ui.theme.beroShape
import com.example.bero.ui.theme.beroWeight

/** Menu contextual (pulsacion larga sobre una pista). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenuSheet(
    track: Track,
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
    onSearchLyrics: () -> Unit,
    onEditLrc: () -> Unit,
    onAddToFolder: () -> Unit,
    onRemoveFromFolder: (() -> Unit)?,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    // Letras TTML (AMLL TTML DB): sistema independiente del LRC; los parametros por defecto no alteran otros usos.
    hasTtml: Boolean = false,
    onSearchTtml: () -> Unit = {},
    onDownloadTtml: () -> Unit = {},
    onRemoveTtml: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(track, Modifier.size(52.dp), corner = 14.dp, px = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontWeight = beroWeight(FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            MenuAction(Icons.Rounded.Info, "Ver información", onInfo)
            MenuAction(Icons.Rounded.CloudDownload, "Buscar letra en línea", onSearchLyrics)
            MenuAction(Icons.Rounded.Search, "Buscar letras TTML en línea", onSearchTtml)
            MenuAction(Icons.Rounded.CloudDownload, "Descargar letras sincronizadas (.TTML)", onDownloadTtml)
            if (hasTtml) MenuAction(Icons.Rounded.RemoveCircleOutline, "Quitar letra TTML descargada", onRemoveTtml)
            MenuAction(Icons.Rounded.Edit, "Editar letra (LRC)", onEditLrc)
            MenuAction(Icons.Rounded.CreateNewFolder, "Añadir a carpeta", onAddToFolder)
            if (onRemoveFromFolder != null) {
                MenuAction(Icons.Rounded.RemoveCircleOutline, "Quitar de esta carpeta", onRemoveFromFolder)
            }
            MenuAction(Icons.Rounded.Share, "Compartir", onShare)
            MenuAction(Icons.Rounded.Delete, "Eliminar", onDelete, destructive = true)
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .pressable(beroShape(18.dp), 0.97f, onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Metadatos detallados: ruta, tamano, formato, duracion... */
@Composable
fun TrackInfoDialog(vm: BeroViewModel, track: Track, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val info by produceState<TrackInfo?>(initialValue = null, track.id) {
        value = vm.trackInfo(track)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text("Información", fontWeight = beroWeight(FontWeight.Bold)) },
        text = {
            val i = info
            if (i == null) {
                Text("Cargando…")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    InfoRow("Título", i.title)
                    InfoRow("Artista", i.artist)
                    InfoRow("Álbum", i.album)
                    InfoRow("Ruta", i.path)
                    InfoRow("Tamaño", Formatter.formatFileSize(ctx, i.sizeBytes))
                    InfoRow(
                        "Formato",
                        listOfNotNull(
                            i.format.ifEmpty { null },
                            i.mime.ifEmpty { null },
                            i.bitrateKbps?.let { "$it kbps" }
                        ).joinToString(" · ").ifEmpty { "—" }
                    )
                    InfoRow("Duración", formatTime(i.durationMs))
                    if (i.dateAddedSec > 0) {
                        InfoRow(
                            "Añadida",
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(i.dateAddedSec * 1000))
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = beroWeight(FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(value.ifEmpty { "—" }, fontSize = 14.sp)
    }
}

@Composable
fun DeleteConfirmDialog(track: Track, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Eliminar canción?", fontWeight = beroWeight(FontWeight.Bold)) },
        text = {
            Text(
                "\"${track.title}\" se borrará físicamente del dispositivo. " +
                    "Android te pedirá confirmación y no se puede deshacer."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
