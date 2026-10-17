package com.example.bero.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.AccentColor
import com.example.bero.data.ThemeMode
import com.example.bero.data.ViewMode
import com.example.bero.lastfm.LastFmConfig
import com.example.bero.ui.LastFmStatus
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.BeroButton
import com.example.bero.ui.theme.appBackground
import com.example.bero.ui.components.BeroChip
import com.example.bero.ui.components.ColorPickerDialog
import com.example.bero.ui.components.GlassCard
import com.example.bero.ui.components.ScreenHeader
import com.example.bero.ui.components.pressable

@Composable
fun SettingsScreen(vm: BeroViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val mode by vm.settings.themeMode.collectAsStateWithLifecycle()
    val accentArgb by vm.settings.accentArgb.collectAsStateWithLifecycle()
    val gradient by vm.settings.gradientBackground.collectAsStateWithLifecycle()
    val viewMode by vm.settings.viewMode.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showPicker by remember { mutableStateOf(false) }
    val minSeconds by vm.settings.minSeconds.collectAsStateWithLifecycle()
    val scanning by vm.isScanning.collectAsStateWithLifecycle()
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val visible by vm.tracks.collectAsStateWithLifecycle()
    val spectrogram by vm.settings.spectrogramEnabled.collectAsStateWithLifecycle()
    val lastFmKey by vm.settings.lastFmApiKey.collectAsStateWithLifecycle()
    val lastFmStatus by vm.lastFmStatus.collectAsStateWithLifecycle()
    val lastFmMessage by vm.lastFmMessage.collectAsStateWithLifecycle()
    val columns by vm.settings.gridColumns.collectAsStateWithLifecycle()

    var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allFilesGranted = hasAllFilesAccess() }

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            // Absorbe toques para que nada llegue a la pantalla de atras.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenHeader("Ajustes", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ------------------------------------------------ Tema
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Tema")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        BeroChip(m.label, selected = m == mode, onClick = { vm.settings.setThemeMode(m) })
                    }
                }
            }

            // ------------------------------------------------ Color de acento (selector libre)
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Color de acento")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AccentColor.entries.forEach { a ->
                        val selected = accentArgb == a.color.toArgb()
                        Box(
                            Modifier
                                .size(46.dp)
                                .pressable(CircleShape, 0.85f) { vm.settings.setAccentArgb(a.color.toArgb()) }
                                .background(a.color, CircleShape)
                                .then(
                                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(Icons.Rounded.Check, a.label, tint = Color.Black.copy(alpha = 0.7f))
                            }
                        }
                    }
                    // Circulo cromatico: cualquier color
                    val isCustom = AccentColor.entries.none { it.color.toArgb() == accentArgb }
                    Box(
                        Modifier
                            .size(46.dp)
                            .pressable(CircleShape, 0.85f) { showPicker = true }
                            .background(Brush.sweepGradient(List(7) { Color.hsv(it * 60f, 1f, 1f) }), CircleShape)
                            .then(
                                if (isCustom) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Colorize, "Selector de color libre", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Toca el círculo cromático para elegir cualquier color. La app ajusta el contraste automáticamente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ------------------------------------------------ Fondo con degradado
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Fondo con degradado")
                        Text(
                            "Un degradado sutil basado en tu color en el fondo de la app, con contraste cuidado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = gradient, onCheckedChange = { vm.settings.setGradientBackground(it) })
                }
            }

            // ------------------------------------------------ Vista de listas
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Vista de las listas")
                Text(
                    "Se aplica a Pistas, Favoritos, álbumes y tus carpetas propias. También puedes cambiarlo al instante con el icono de cuadrícula de la barra superior.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ViewMode.entries.forEach { m ->
                        BeroChip(m.label, selected = m == viewMode, onClick = { vm.settings.setViewMode(m) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (columns == 1) "Tamaño: lista (1 columna)" else "Tamaño: $columns columnas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = columns.toFloat(),
                    onValueChange = { vm.settings.setGridColumns(Math.round(it)) },
                    valueRange = 1f..6f,
                    steps = 4,
                    enabled = viewMode == ViewMode.GRID
                )
            }

            // ------------------------------------------------ Filtro de duracion
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Ocultar audios menores a $minSeconds s")
                Text(
                    "Evita notas de voz de WhatsApp y sonidos cortos. Mostrando ${visible.size} de $total audios.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = minSeconds.toFloat(),
                    onValueChange = { vm.settings.setMinSeconds(it.toInt()) },
                    valueRange = 0f..120f
                )
            }

            // ------------------------------------------------ Espectrograma
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Espectrograma")
                        Text(
                            "Si lo desactivas, el Visualizer no se crea: sin hilo de FFT, sin uso de CPU ni de micrófono.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = spectrogram,
                        onCheckedChange = { vm.settings.setSpectrogramEnabled(it) }
                    )
                }
            }

            // ------------------------------------------------ Last.fm / Smart Shuffle
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Smart Shuffle · Last.fm")
                Text(
                    "Gratis y sin iniciar sesión: Last.fm analiza tus favoritas y los artistas de los que más música tienes " +
                        "y recomienda en línea canciones y artistas NUEVOS (pestaña Descubrir, con vista previa de 30 s). " +
                        "Smart Shuffle además intercala en la fila las parecidas que sí tienes. Solo necesitas una API key gratuita.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = lastFmKey,
                    onValueChange = vm::onLastFmKeyChanged,
                    label = { Text("API key de Last.fm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Estado: " + when (lastFmStatus) {
                        LastFmStatus.NO_KEY -> "sin API key (usa tus favoritos)"
                        LastFmStatus.READY -> "lista"
                        LastFmStatus.LOADING -> "consultando…"
                        LastFmStatus.ERROR -> "error"
                    } + (lastFmMessage?.let { " · $it" } ?: ""),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                BeroChip("Obtener API key gratis", selected = false, onClick = {
                    safeStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(LastFmConfig.SIGNUP_URL)))
                })
                Spacer(Modifier.height(12.dp))
                BeroButton("Probar conexión", onClick = { vm.testLastFm() })
                Spacer(Modifier.height(8.dp))
                BeroButton("Actualizar sugerencias", onClick = { vm.refreshLastFm() })
            }

            // ------------------------------------------------ Escaneo
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Biblioteca")
                Text(
                    "Si copiaste música nueva y no aparece, fuerza un escaneo de la memoria interna. " +
                        "La carpeta Android y las carpetas ocultas se omiten siempre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                BeroButton(
                    text = if (scanning) "Escaneando…" else "Escanear música nueva",
                    onClick = { if (!scanning) vm.rescan() },
                    icon = Icons.Rounded.Refresh
                )
            }

            // ------------------------------------------------ Segundo plano (Xiaomi / HyperOS)
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Reproducción en segundo plano")
                Text(
                    "HyperOS cierra apps en segundo plano de forma agresiva. Quita la optimización de batería " +
                        "para Bero y, en la info de la app, pon el ahorro de batería en \"Sin restricciones\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                BeroButton("Optimización de batería", onClick = {
                    safeStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                })
                Spacer(Modifier.height(8.dp))
                BeroButton("Info de la app", onClick = {
                    safeStart(
                        ctx,
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                })
            }

            // ------------------------------------------------ Letras .lrc locales
            if (Build.VERSION.SDK_INT >= 30) {
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle("Letras .lrc locales")
                    Text(
                        "Para leer archivos .lrc junto a tus canciones, Android 11+ exige \"acceso a todos los archivos\". " +
                            "Sin él, las letras se descargan de LRCLIB. Estado: " +
                            if (allFilesGranted) "concedido." else "no concedido.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!allFilesGranted) {
                        Spacer(Modifier.height(12.dp))
                        BeroButton("Permitir acceso a archivos", onClick = {
                            val appIntent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")
                            )
                            if (!safeStart(ctx, appIntent)) {
                                safeStart(ctx, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            initial = Color(accentArgb),
            onDismiss = { showPicker = false },
            onConfirm = {
                vm.settings.setAccentArgb(it.toArgb())
                showPicker = false
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

private fun safeStart(ctx: android.content.Context, intent: Intent): Boolean =
    try {
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
