package com.example.bero.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.audio.AudioFx
import com.example.bero.ui.components.BeroButton
import com.example.bero.ui.components.BeroChip
import com.example.bero.ui.theme.beroShape
import com.example.bero.ui.theme.beroWeight

private fun bandLabel(hz: Int): String =
    if (hz >= 1000) "%.1f kHz".format(java.util.Locale.US, hz / 1000f) else "$hz Hz"

/** Ecualizador interno nativo (android.media.audiofx.Equalizer) como ventana superpuesta. */
@Composable
fun EqualizerDialog(onDismiss: () -> Unit) {
    val st by AudioFx.state.collectAsStateWithLifecycle()
    val shape = beroShape(28.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ecualizador",
                    fontSize = 22.sp,
                    fontWeight = beroWeight(FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = st.enabled,
                    onCheckedChange = { AudioFx.setEnabled(it) },
                    enabled = st.available
                )
            }
            Spacer(Modifier.height(12.dp))

            if (!st.available) {
                Text(
                    "El ecualizador aún no está disponible. Reproduce una canción e inténtalo de nuevo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (st.presets.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        st.presets.forEachIndexed { index, name ->
                            BeroChip(name, selected = st.currentPreset == index, onClick = { AudioFx.usePreset(index) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                st.bands.forEach { band ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            bandLabel(band.centerHz),
                            modifier = Modifier.width(68.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = band.level.toFloat(),
                            onValueChange = { AudioFx.setBand(band.index, it.toInt()) },
                            valueRange = st.minLevel.toFloat()..st.maxLevel.toFloat(),
                            enabled = st.enabled,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${band.level / 100} dB",
                            modifier = Modifier.width(54.dp),
                            textAlign = TextAlign.End,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                BeroChip("Restablecer", selected = false, onClick = { AudioFx.reset() })
            }

            Spacer(Modifier.height(16.dp))
            BeroButton("Cerrar", onDismiss)
        }
    }
}
