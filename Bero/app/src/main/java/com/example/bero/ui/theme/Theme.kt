package com.example.bero.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.example.bero.data.ThemeMode

@Composable
fun BeroTheme(mode: ThemeMode, accent: Color, content: @Composable () -> Unit) {
    val onAccent = if (accent.luminance() > 0.45f) Color(0xFF101014) else Color.White

    val scheme = when (mode) {
        ThemeMode.LIGHT -> lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = lerp(Color.White, accent, 0.18f),
            onPrimaryContainer = Color(0xFF1B1B1F),
            secondary = accent,
            background = Color(0xFFF7F5FB),
            onBackground = Color(0xFF1B1B1F),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1B1B1F),
            surfaceVariant = Color(0xFFE9E5F0),
            onSurfaceVariant = Color(0xFF49454F),
            outline = Color(0xFF79747E),
            surfaceContainer = Color(0xFFF0ECF6),
            surfaceContainerHigh = Color(0xFFEAE6F0),
        )

        ThemeMode.DARK -> darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = lerp(Color(0xFF16161B), accent, 0.30f),
            onPrimaryContainer = Color.White,
            secondary = accent,
            background = Color(0xFF0E0E12),
            onBackground = Color(0xFFE6E1E9),
            surface = Color(0xFF16161B),
            onSurface = Color(0xFFE6E1E9),
            surfaceVariant = Color(0xFF24242B),
            onSurfaceVariant = Color(0xFFC9C5D0),
            outline = Color(0xFF938F99),
            surfaceContainer = Color(0xFF1C1C22),
            surfaceContainerHigh = Color(0xFF26262D),
        )

        ThemeMode.COLOR -> {
            // Tema oscuro teñido con el color de acento (fondo y superficies).
            val bg = lerp(Color(0xFF0A0A0F), accent, 0.20f)
            darkColorScheme(
                primary = lerp(accent, Color.White, 0.15f),
                onPrimary = Color(0xFF101014),
                primaryContainer = lerp(bg, accent, 0.35f),
                onPrimaryContainer = Color.White,
                secondary = accent,
                background = bg,
                onBackground = Color(0xFFF1ECF5),
                surface = lerp(bg, accent, 0.10f),
                onSurface = Color(0xFFF1ECF5),
                surfaceVariant = lerp(bg, accent, 0.24f),
                onSurfaceVariant = Color(0xFFDCD6E2),
                outline = Color(0xFFA9A3B0),
                surfaceContainer = lerp(bg, accent, 0.16f),
                surfaceContainerHigh = lerp(bg, accent, 0.22f),
            )
        }
    }

    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
