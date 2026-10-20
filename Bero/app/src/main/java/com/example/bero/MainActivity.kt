package com.example.bero

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.ArtworkLoader
import com.example.bero.data.ThemeMode
import com.example.bero.ui.BeroApp
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.components.RemoteArtLoader
import com.example.bero.ui.theme.BeroTheme

class MainActivity : ComponentActivity() {

    private val vm: BeroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val mode by vm.settings.themeMode.collectAsStateWithLifecycle()
            val accentArgb by vm.settings.accentArgb.collectAsStateWithLifecycle()
            val gradient by vm.settings.gradientBackground.collectAsStateWithLifecycle()
            val style by vm.settings.themeStyle.collectAsStateWithLifecycle()
            val expanded by vm.playerExpanded.collectAsStateWithLifecycle()

            // El reproductor siempre es oscuro: iconos de barra claros aunque el tema sea "Claro".
            val darkBars = mode != ThemeMode.LIGHT || expanded
            DisposableEffect(darkBars) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (darkBars) {
                    SystemBarStyle.dark(transparent)
                } else {
                    SystemBarStyle.light(transparent, transparent)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            BeroTheme(mode = mode, accent = Color(accentArgb), gradient = gradient, style = style) {
                BeroApp(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForeground() // vuelve a enganchar el listener de la UI y se pone al dia
    }

    override fun onStop() {
        vm.saveProgressNow()
        vm.onAppBackground() // desengancha el listener de la UI: en segundo plano solo queda el nucleo minimo
        super.onStop()
    }

    /** El sistema pide memoria: se sueltan las cachés de imagenes (se recargan solas al volver). */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ArtworkLoader.trim(level)
        RemoteArtLoader.trim(level)
    }
}
