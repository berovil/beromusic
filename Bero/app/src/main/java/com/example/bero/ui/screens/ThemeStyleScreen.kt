package com.example.bero.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.ThemeStyle
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.ScreenHeader
import com.example.bero.ui.components.pressable
import com.example.bero.ui.theme.BeroTheme
import com.example.bero.ui.theme.appBackground
import com.example.bero.ui.theme.beroShape
import com.example.bero.ui.theme.beroSurface
import com.example.bero.ui.theme.beroWeight

/**
 * Subpantalla dedicada a elegir el ESTILO de tema (Clasico, Minimalista, Pixel y Liquid Glass).
 * Cada opcion muestra una vista previa REAL: se renderiza dentro de un BeroTheme anidado con ese estilo,
 * en el mismo modo Claro/Oscuro y con el mismo acento que el usuario tiene ahora.
 */
@Composable
fun ThemeStyleScreen(vm: BeroViewModel, onBack: () -> Unit) {
    val current by vm.settings.themeStyle.collectAsStateWithLifecycle()
    val accentArgb by vm.settings.accentArgb.collectAsStateWithLifecycle()
    val accent = remember(accentArgb) { Color(accentArgb) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            // Absorbe toques para que nada llegue a la pantalla de atras.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenHeader("Estilo de tema", "Cambia la estética completa de la app", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(ThemeStyle.entries, key = { it.name }) { style ->
                StyleOptionCard(
                    style = style,
                    selected = style == current,
                    accent = accent,
                    dark = dark,
                    onSelect = { vm.settings.setThemeStyle(style) }
                )
            }
        }
    }
}

@Composable
private fun StyleOptionCard(
    style: ThemeStyle,
    selected: Boolean,
    accent: Color,
    dark: Boolean,
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = beroShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(shape, 0.98f, onSelect)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) cs.primary else cs.outline.copy(alpha = 0.35f),
                shape
            )
            .padding(12.dp)
    ) {
        StylePreview(
            style, accent, dark,
            Modifier
                .fillMaxWidth()
                .height(124.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    style.label,
                    fontSize = 17.sp,
                    fontWeight = beroWeight(FontWeight.Bold),
                    color = if (selected) cs.primary else cs.onSurface
                )
                Text(
                    style.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = "Seleccionado", tint = cs.primary)
            }
        }
    }
}

/** Mini-pantalla renderizada con el tema del estilo [style] (BeroTheme anidado). */
@Composable
private fun StylePreview(style: ThemeStyle, accent: Color, dark: Boolean, modifier: Modifier) {
    BeroTheme(dark = dark, accent = accent, gradient = false, style = style, artworkTint = accent) {
        val cs = MaterialTheme.colorScheme
        Box(
            modifier
                .clip(beroShape(16.dp))
                .appBackground()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // tarjeta de cancion
                Row(
                    Modifier
                        .fillMaxWidth()
                        .beroSurface(14.dp)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(beroShape(8.dp))
                            .background(cs.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Canción", fontSize = 12.sp, fontWeight = beroWeight(FontWeight.SemiBold))
                        Text("Artista", fontSize = 10.sp, color = cs.onSurfaceVariant)
                    }
                }
                // botones
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .clip(beroShape(12.dp))
                            .background(cs.primary)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Play", fontSize = 11.sp, color = cs.onPrimary, fontWeight = beroWeight(FontWeight.Bold))
                    }
                    Box(
                        Modifier
                            .border(1.dp, cs.outline, beroShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Ajustes", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
