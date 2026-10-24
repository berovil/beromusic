package com.example.bero

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
            val tintArgb by vm.artworkTint.collectAsStateWithLifecycle()

            // Sistema / Claro / Oscuro -> un unico Boolean. `remember` con claves: solo se recalcula si cambia el modo
            // o el tema del sistema; el resto de la app NO depende del modo elegido, solo de este resultado.
            val systemDark = isSystemInDarkTheme()
            val dark = remember(mode, systemDark) {
                when (mode) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            }
            val accent = remember(accentArgb) { Color(accentArgb) }
            val tint = remember(tintArgb) { tintArgb?.let { Color(it) } }

            // Iconos de las barras del sistema segun el modo (el reproductor ya sigue el mismo modo).
            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val barStyle = if (dark) {
                    SystemBarStyle.dark(transparent)
                } else {
                    SystemBarStyle.light(transparent, transparent)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                onDispose { }
            }

            BeroTheme(dark = dark, accent = accent, gradient = gradient, style = style, artworkTint = tint) {
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
