package com.example.bero.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * Estado de visibilidad del engranaje:
 *  - al desplazar la lista HACIA ABAJO (dedo hacia arriba) se oculta;
 *  - en cuanto se desliza levemente HACIA ARRIBA vuelve a aparecer al instante, sin tener que llegar al inicio.
 * Es un NestedScrollConnection que NO consume nada: solo observa la direccion.
 */
@Stable
class ScrollVisibilityState {
    var visible by mutableStateOf(true)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -6f) visible = false else if (available.y > 6f) visible = true
            return Offset.Zero
        }
    }
}

/** Contenedor de pantalla con el engranaje de Ajustes flotante que se oculta/muestra segun el scroll. */
@Composable
fun ScrollAwareBox(
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { ScrollVisibilityState() }
    Box(
        modifier
            .fillMaxSize()
            .nestedScroll(state.connection)
    ) {
        content()
        if (onOpenSettings != null) {
            FloatingSettingsButton(state, onOpenSettings, Modifier.align(Alignment.TopEnd))
        }
    }
}

// La lectura de `state.visible` vive SOLO aqui: cambiar la visibilidad no recompone la lista.
@Composable
private fun FloatingSettingsButton(state: ScrollVisibilityState, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier.padding(top = 10.dp, end = 12.dp),
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape)
        ) {
            BeroIconButton(Icons.Rounded.Settings, "Ajustes", onClick)
        }
    }
}
