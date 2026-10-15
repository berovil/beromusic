package com.example.bero.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.LrcParser
import com.example.bero.data.Track
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.BeroButton
import com.example.bero.ui.theme.appBackground
import com.example.bero.ui.components.BeroChip
import com.example.bero.ui.components.ScreenHeader
import kotlinx.coroutines.launch

/** Editor de texto basico para el .lrc asociado a una cancion. */
@Composable
fun LrcEditorScreen(vm: BeroViewModel, track: Track, onClose: () -> Unit) {
    var text by remember(track.id) { mutableStateOf(TextFieldValue("")) }
    var loaded by remember(track.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playing by vm.currentTrack.collectAsStateWithLifecycle()

    LaunchedEffect(track.id) {
        text = TextFieldValue(vm.loadLrcText(track))
        loaded = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            // Absorbe toques para que nada llegue a la pantalla de atras.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        ScreenHeader("Editar letra (LRC)", track.title, onBack = onClose)

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = loaded,
            placeholder = {
                Text("[00:12.50]Primera línea\n[00:17.80]Segunda línea")
            },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (playing?.id == track.id) {
                BeroChip("Insertar marca de tiempo", selected = false, onClick = {
                    val tag = LrcParser.formatTag(vm.currentPositionMs())
                    val sel = text.selection
                    val updated = text.text.replaceRange(sel.min, sel.max, tag)
                    text = TextFieldValue(updated, TextRange(sel.min + tag.length))
                })
            } else {
                Text(
                    "Reproduce esta canción para poder insertar la marca de tiempo actual.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            BeroButton("Guardar", onClick = {
                scope.launch {
                    vm.saveLrc(track, text.text)
                    onClose()
                }
            })
        }
    }
}
