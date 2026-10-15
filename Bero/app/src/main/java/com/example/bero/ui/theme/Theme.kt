package com.example.bero.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.example.bero.data.ThemeMode
import kotlin.math.max
import kotlin.math.min

/** true => el fondo de la app es un degradado sutil basado en el color de acento. */
val LocalGradientBackground = compositionLocalOf { false }

private fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
}

/**
 * El usuario puede elegir CUALQUIER color. Para que el acento siga siendo legible como texto/icono sobre el
 * fondo, se acerca progresivamente a blanco (temas oscuros) o negro (tema claro) hasta contraste >= 3:1.
 */
private fun readableAccent(accent: Color, background: Color, towards: Color): Color {
    var c = accent
    var i = 0
    while (contrastRatio(c, background) < 3f && i < 14) {
        c = lerp(c, towards, 0.12f)
        i++
    }
    return c
}

@Composable
fun BeroTheme(
    mode: ThemeMode,
    accent: Color,
    gradient: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = remember(mode, accent) {
        when (mode) {
            ThemeMode.LIGHT -> {
                val bg = Color(0xFFF7F5FB)
                val primary = readableAccent(accent, bg, Color.Black)
                lightColorScheme(
                    primary = primary,
                    onPrimary = if (primary.luminance() > 0.45f) Color(0xFF101014) else Color.White,
                    primaryContainer = lerp(Color.White, primary, 0.18f),
                    onPrimaryContainer = Color(0xFF1B1B1F),
                    secondary = primary,
                    background = bg,
                    onBackground = Color(0xFF1B1B1F),
                    surface = Color(0xFFFFFFFF),
                    onSurface = Color(0xFF1B1B1F),
                    surfaceVariant = Color(0xFFE9E5F0),
                    onSurfaceVariant = Color(0xFF49454F),
                    outline = Color(0xFF79747E),
                    surfaceContainer = Color(0xFFF0ECF6),
                    surfaceContainerHigh = Color(0xFFEAE6F0),
                )
            }

            ThemeMode.DARK -> {
                val bg = Color(0xFF0E0E12)
                val primary = readableAccent(accent, bg, Color.White)
                darkColorScheme(
                    primary = primary,
                    onPrimary = if (primary.luminance() > 0.45f) Color(0xFF101014) else Color.White,
                    primaryContainer = lerp(Color(0xFF16161B), primary, 0.30f),
                    onPrimaryContainer = Color.White,
                    secondary = primary,
                    background = bg,
                    onBackground = Color(0xFFE6E1E9),
                    surface = Color(0xFF16161B),
                    onSurface = Color(0xFFE6E1E9),
                    surfaceVariant = Color(0xFF24242B),
                    onSurfaceVariant = Color(0xFFC9C5D0),
                    outline = Color(0xFF938F99),
                    surfaceContainer = Color(0xFF1C1C22),
                    surfaceContainerHigh = Color(0xFF26262D),
                )
            }

            ThemeMode.COLOR -> {
                // Tema oscuro teñido con el color elegido (fondo y superficies).
                val bg = lerp(Color(0xFF0A0A0F), accent, 0.20f)
                val primary = readableAccent(lerp(accent, Color.White, 0.15f), bg, Color.White)
                darkColorScheme(
                    primary = primary,
                    onPrimary = if (primary.luminance() > 0.45f) Color(0xFF101014) else Color.White,
                    primaryContainer = lerp(bg, accent, 0.35f),
                    onPrimaryContainer = Color.White,
                    secondary = primary,
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
    }

    CompositionLocalProvider(LocalGradientBackground provides gradient) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

/**
 * Fondo de la app: color plano o, si el usuario lo activa, un degradado vertical sutil (maximo ~20 % de mezcla
 * con el acento) que deja intacto el contraste del texto.
 */
fun Modifier.appBackground(): Modifier = composed {
    val cs = MaterialTheme.colorScheme
    if (LocalGradientBackground.current) {
        val dark = cs.background.luminance() < 0.5f
        val strength = if (dark) 0.22f else 0.16f
        val brush = remember(cs.background, cs.primary, strength) {
            Brush.verticalGradient(
                listOf(
                    lerp(cs.background, cs.primary, strength),
                    lerp(cs.background, cs.primary, strength * 0.35f),
                    cs.background,
                )
            )
        }
        background(brush)
    } else {
        background(cs.background)
    }
}
