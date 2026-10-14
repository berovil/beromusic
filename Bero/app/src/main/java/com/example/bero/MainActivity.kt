package com.example.bero

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bero.data.ThemeMode
import com.example.bero.ui.BeroApp
import com.example.bero.ui.BeroViewModel
import com.example.bero.ui.theme.BeroTheme

class MainActivity : ComponentActivity() {

    private val vm: BeroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleSpotifyRedirect(intent)

        setContent {
            val mode by vm.settings.themeMode.collectAsStateWithLifecycle()
            val accent by vm.settings.accent.collectAsStateWithLifecycle()
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

            BeroTheme(mode = mode, accent = accent.color) {
                BeroApp(vm)
            }
        }
    }

    // launchMode="singleTop": el navegador vuelve aqui con com.example.bero://spotify-callback?code=...
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent)
    }

    private fun handleSpotifyRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "com.example.bero" && data.host == "spotify-callback") {
            intent.data = null // evita reprocesar el codigo al rotar la pantalla
            vm.handleSpotifyRedirect(data)
        }
    }

    override fun onStop() {
        vm.saveProgressNow()
        super.onStop()
    }
}
