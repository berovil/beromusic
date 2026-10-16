package com.example.bero.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewHeadline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bero.data.SmartShuffle
import com.example.bero.data.Track
import com.example.bero.data.ViewMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

object LibraryBarDefaults {
    /** Altura de la barra: las listas la reservan como padding superior para no quedar tapadas. */
    val Height = 64.dp
}

/**
 * Estado de la barra superior de la biblioteca:
 *  - se OCULTA al desplazar la lista hacia abajo y REAPARECE en cuanto se desliza levemente hacia arriba;
 *  - no se oculta mientras se busca o se ajusta el tamano de la vista.
 * El NestedScrollConnection solo observa la direccion (no consume nada) y solo escribe un Boolean.
 */
@Stable
class LibraryBarState {
    var visible by mutableStateOf(true)
    var searchOpen by mutableStateOf(false)
    var sliderOpen by mutableStateOf(false)
    var rawQuery by mutableStateOf("")

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (searchOpen || sliderOpen) return Offset.Zero
            if (available.y < -6f) visible = false else if (available.y > 6f) visible = true
            return Offset.Zero
        }
    }

    fun closeSearch() {
        searchOpen = false
        rawQuery = ""
    }
}

@Composable
fun rememberLibraryBarState(): LibraryBarState = remember { LibraryBarState() }

/** Consulta normalizada (sin acentos) con debounce de 120 ms: filtrar no se hace en cada pulsacion. */
@Composable
fun rememberSearchQuery(state: LibraryBarState): String {
    var query by remember { mutableStateOf("") }
    val raw = state.rawQuery
    LaunchedEffect(raw) {
        if (raw.isBlank()) {
            query = ""
        } else {
            delay(120)
            query = SmartShuffle.norm(raw)
        }
    }
    return query
}

/** Todas las palabras de la consulta deben aparecer en la clave precalculada de la pista. */
fun List<Track>.filterByQuery(query: String): List<Track> {
    if (query.isEmpty()) return this
    val tokens = query.split(' ').filter { it.isNotEmpty() }
    return filter { t -> tokens.all { it in t.searchKey } }
}

fun matchesQuery(text: String, query: String): Boolean {
    if (query.isEmpty()) return true
    val key = SmartShuffle.norm(text)
    return query.split(' ').filter { it.isNotEmpty() }.all { it in key }
}

/** Controles del slider "tipo Explorador de Windows": 1 columna = lista; 2..6 = cuadricula; o carrusel. */
@Immutable
class GridControls(
    val columns: Int,
    val carousel: Boolean,
    val onColumns: (Int) -> Unit,
    val onCarousel: (Boolean) -> Unit,
) {
    val viewMode: ViewMode get() = if (carousel) ViewMode.CAROUSEL else ViewMode.GRID
}

@Composable
fun LibraryScaffold(
    title: String,
    subtitle: String?,
    state: LibraryBarState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    searchEnabled: Boolean = true,
    gridControls: GridControls? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    BackHandler(enabled = state.searchOpen) { state.closeSearch() }

    Box(
        modifier
            .fillMaxSize()
            .nestedScroll(state.connection)
    ) {
        content()
        LibraryTopBar(
            state = state,
            title = title,
            subtitle = subtitle,
            onOpenSettings = onOpenSettings,
            onBack = onBack,
            searchEnabled = searchEnabled,
            gridControls = gridControls,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

// Las lecturas de `state.visible / searchOpen / sliderOpen` viven SOLO aqui: mostrar u ocultar la barra
// recompone esta funcion, nunca la lista que hay debajo.
@Composable
private fun LibraryTopBar(
    state: LibraryBarState,
    title: String,
    subtitle: String?,
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)?,
    searchEnabled: Boolean,
    gridControls: GridControls?,
    modifier: Modifier,
) {
    val show = state.visible || state.searchOpen || state.sliderOpen
    AnimatedVisibility(
        visible = show,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        val shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(LibraryBarDefaults.Height)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.searchOpen) {
                    SearchField(state, Modifier.weight(1f))
                } else {
                    if (onBack != null) {
                        BeroIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás", onBack)
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (searchEnabled) {
                    BeroIconButton(
                        icon = if (state.searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = if (state.searchOpen) "Cerrar búsqueda" else "Buscar",
                        onClick = {
                            if (state.searchOpen) {
                                state.closeSearch()
                            } else {
                                state.searchOpen = true
                                state.visible = true
                            }
                        }
                    )
                }
                if (gridControls != null) {
                    BeroIconButton(
                        icon = Icons.Rounded.GridView,
                        contentDescription = "Tamaño de la vista",
                        onClick = { state.sliderOpen = !state.sliderOpen },
                        active = state.sliderOpen
                    )
                }
                BeroIconButton(Icons.Rounded.Settings, "Ajustes", onOpenSettings)
            }

            if (gridControls != null) {
                AnimatedVisibility(
                    visible = state.sliderOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    GridSliderPanel(gridControls)
                }
            }
        }
    }
}

@Composable
private fun SearchField(state: LibraryBarState, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    OutlinedTextField(
        value = state.rawQuery,
        onValueChange = { state.rawQuery = it },
        singleLine = true,
        placeholder = { Text("Buscar canciones, artistas, álbumes…") },
        shape = RoundedCornerShape(26.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier
            .padding(start = 8.dp)
            .focusRequester(focus)
    )
}

/** Slider de tamano: de 1 columna (lista) a 6 columnas (caratulas pequenas). Los cambios se reflejan al instante. */
@Composable
private fun GridSliderPanel(g: GridControls) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ViewHeadline,
                contentDescription = "Lista",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Slider(
                value = g.columns.toFloat(),
                onValueChange = { g.onColumns(it.roundToInt()) },
                valueRange = 1f..6f,
                steps = 4,
                enabled = !g.carousel,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Icon(
                Icons.Rounded.GridView,
                contentDescription = "Cuadrícula",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (g.carousel) "Carrusel" else if (g.columns == 1) "Lista" else "${g.columns} columnas",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            BeroChip("Carrusel", selected = g.carousel, onClick = { g.onCarousel(!g.carousel) })
        }
    }
}
