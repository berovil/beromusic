package com.example.bero.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.bero.data.ThemeMode
import com.example.bero.data.ThemeStyle
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
 * fondo, se acerca progresivamente a blanco o negro hasta contraste >= 3:1.
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

private fun onColor(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF101014) else Color.White

private fun hue(c: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return hsv[0]
}

// ============================================================================ ESQUEMAS DE COLOR

/** Tema Clasico / Dinamico (el de siempre): integra el selector de color libre. */
private fun classicScheme(mode: ThemeMode, accent: Color): ColorScheme = when (mode) {
    ThemeMode.LIGHT -> {
        val bg = Color(0xFFF7F5FB)
        val primary = readableAccent(accent, bg, Color.Black)
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
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
            onPrimary = onColor(primary),
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
            onPrimary = onColor(primary),
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

/** Minimalista (Niagara): negro profundo o blanco puro, alto contraste, casi sin superficies. */
private fun minimalScheme(mode: ThemeMode, accent: Color): ColorScheme =
    if (mode != ThemeMode.LIGHT) {
        val bg = Color.Black
        val primary = readableAccent(accent, bg, Color.White)
        darkColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = Color(0xFF1A1A1A),
            onPrimaryContainer = Color.White,
            secondary = primary,
            background = bg,
            onBackground = Color.White,
            surface = bg,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF141414),
            onSurfaceVariant = Color(0xFFB4B4B4),
            outline = Color(0xFF3A3A3A),
            surfaceContainer = Color(0xFF0A0A0A),
            surfaceContainerHigh = Color(0xFF121212),
        )
    } else {
        val bg = Color.White
        val primary = readableAccent(accent, bg, Color.Black)
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = Color(0xFFF0F0F0),
            onPrimaryContainer = Color.Black,
            secondary = primary,
            background = bg,
            onBackground = Color.Black,
            surface = bg,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFF2F2F2),
            onSurfaceVariant = Color(0xFF5C5C5C),
            outline = Color(0xFFBDBDBD),
            surfaceContainer = Color(0xFFF7F7F7),
            surfaceContainerHigh = Color(0xFFEFEFEF),
        )
    }

/** Pixel / Retro 8-bit: colores saturados "arcade" derivados del matiz elegido. */
private fun pixelScheme(mode: ThemeMode, accent: Color): ColorScheme {
    val h = hue(accent)
    return if (mode != ThemeMode.LIGHT) {
        val bg = Color.hsv(h, 0.70f, 0.12f)
        val primary = Color.hsv(h, 0.90f, 1f) // neon
        val secondary = Color.hsv((h + 120f) % 360f, 0.90f, 1f)
        val tertiary = Color.hsv((h + 240f) % 360f, 0.90f, 1f)
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF0B0620),
            primaryContainer = Color.hsv(h, 0.80f, 0.35f),
            onPrimaryContainer = Color.White,
            secondary = secondary,
            tertiary = tertiary,
            background = bg,
            onBackground = Color(0xFFF4F0FF),
            surface = Color.hsv(h, 0.65f, 0.17f),
            onSurface = Color(0xFFF4F0FF),
            surfaceVariant = Color.hsv(h, 0.55f, 0.24f),
            onSurfaceVariant = Color(0xFFD8D0F0),
            outline = primary.copy(alpha = 0.8f),
            surfaceContainer = Color.hsv(h, 0.60f, 0.20f),
            surfaceContainerHigh = Color.hsv(h, 0.55f, 0.26f),
        )
    } else {
        val bg = Color(0xFFFFF4CF) // papel crema retro
        val ink = Color(0xFF1A1033)
        val primary = readableAccent(Color.hsv(h, 1f, 0.75f), bg, Color.Black)
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = Color(0xFFFFE08A),
            onPrimaryContainer = ink,
            secondary = Color.hsv((h + 120f) % 360f, 1f, 0.70f),
            tertiary = Color.hsv((h + 240f) % 360f, 1f, 0.70f),
            background = bg,
            onBackground = ink,
            surface = Color(0xFFFFFBEA),
            onSurface = ink,
            surfaceVariant = Color(0xFFFFE9A8),
            onSurfaceVariant = Color(0xFF4A3F6B),
            outline = ink,
            surfaceContainer = Color(0xFFFFF0BF),
            surfaceContainerHigh = Color(0xFFFFE9A8),
        )
    }
}

/** Pastel / Soft Minimal: lavanda, menta, durazno y rosa suave, calidos y desaturados. */
private fun pastelScheme(mode: ThemeMode, accent: Color): ColorScheme {
    val h = hue(accent)
    val mint = Color(0xFFA8E6CF)
    val peach = Color(0xFFFFCBB0)
    return if (mode != ThemeMode.LIGHT) {
        val bg = Color(0xFF211D27)
        val primary = Color.hsv(h, 0.36f, 0.98f)
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF2A2230),
            primaryContainer = lerp(bg, primary, 0.30f),
            onPrimaryContainer = Color(0xFFF3EAF5),
            secondary = mint,
            tertiary = peach,
            secondaryContainer = lerp(bg, mint, 0.25f),
            tertiaryContainer = lerp(bg, peach, 0.25f),
            background = bg,
            onBackground = Color(0xFFF3EAF5),
            surface = Color(0xFF28232F),
            onSurface = Color(0xFFF3EAF5),
            surfaceVariant = Color(0xFF332D3B),
            onSurfaceVariant = Color(0xFFD7CCDC),
            outline = Color(0xFF9A8FA3),
            surfaceContainer = Color(0xFF2E2935),
            surfaceContainerHigh = Color(0xFF383240),
        )
    } else {
        val bg = Color(0xFFFFF8F4) // crema calido
        val primary = readableAccent(Color.hsv(h, 0.45f, 0.88f), bg, Color.Black)
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = lerp(Color.White, Color.hsv(h, 0.40f, 0.95f), 0.55f),
            onPrimaryContainer = Color(0xFF3B3340),
            secondary = Color(0xFF5FBFA0),
            tertiary = Color(0xFFE79A78),
            secondaryContainer = lerp(Color.White, mint, 0.7f),
            tertiaryContainer = lerp(Color.White, peach, 0.7f),
            background = bg,
            onBackground = Color(0xFF3B3340),
            surface = Color.White,
            onSurface = Color(0xFF3B3340),
            surfaceVariant = Color(0xFFF6EAF4),
            onSurfaceVariant = Color(0xFF7A6E80),
            outline = Color(0xFFC9BDCE),
            surfaceContainer = Color(0xFFFCEFF2),
            surfaceContainerHigh = Color(0xFFF7E8F7),
        )
    }
}

private fun schemeFor(style: ThemeStyle, mode: ThemeMode, accent: Color): ColorScheme = when (style) {
    ThemeStyle.CLASSIC -> classicScheme(mode, accent)
    ThemeStyle.MINIMAL -> minimalScheme(mode, accent)
    ThemeStyle.PIXEL -> pixelScheme(mode, accent)
    ThemeStyle.PASTEL -> pastelScheme(mode, accent)
}

// ============================================================================ TIPOGRAFIA Y FORMAS

private fun Typography.withFontFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

private fun familyFor(style: ThemeStyle): FontFamily? = when (style) {
    ThemeStyle.CLASSIC -> null
    ThemeStyle.MINIMAL -> FontFamily.SansSerif
    ThemeStyle.PIXEL -> FontFamily.Monospace // para el look pixel real, añade una fuente .ttf en res/font
    ThemeStyle.PASTEL -> FontFamily.SansSerif
}

private fun shapesFor(style: ThemeStyle) = Shapes(
    extraSmall = cornerShape(style, 4.dp),
    small = cornerShape(style, 8.dp),
    medium = cornerShape(style, 12.dp),
    large = cornerShape(style, 16.dp),
    extraLarge = cornerShape(style, 28.dp),
)

@Composable
fun BeroTheme(
    mode: ThemeMode,
    accent: Color,
    gradient: Boolean = false,
    style: ThemeStyle = ThemeStyle.CLASSIC,
    content: @Composable () -> Unit,
) {
    val scheme = remember(style, mode, accent) { schemeFor(style, mode, accent) }
    val shapes = remember(style) { shapesFor(style) }
    val family = remember(style) { familyFor(style) }
    val typography = remember(family) { if (family == null) Typography() else Typography().withFontFamily(family) }

    CompositionLocalProvider(
        LocalGradientBackground provides gradient,
        LocalThemeStyle provides style
    ) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography) {
            if (family == null) {
                content()
            } else {
                // Los Text sin `style` explicito tambien adoptan la tipografia del estilo.
                ProvideTextStyle(TextStyle(fontFamily = family), content)
            }
        }
    }
}

/**
 * Fondo de la app: color plano o, si el usuario lo activa, un degradado vertical sutil (maximo ~20 % de mezcla
 * con el acento). El estilo Minimalista siempre usa el fondo puro.
 */
fun Modifier.appBackground(): Modifier = composed {
    val cs = MaterialTheme.colorScheme
    if (LocalGradientBackground.current && LocalThemeStyle.current != ThemeStyle.MINIMAL) {
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
