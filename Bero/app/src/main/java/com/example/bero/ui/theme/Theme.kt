package com.example.bero.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.bero.data.ThemeStyle
import kotlin.math.max
import kotlin.math.min

/** true => el fondo de la app es un degradado sutil basado en el color de acento. */
val LocalGradientBackground = compositionLocalOf { false }

/**
 * Color dominante de la caratula de la cancion activa (solo se calcula con Liquid Glass). Es un CompositionLocal
 * dinamico: al cambiar de cancion solo se recomponen los que lo leen (fondos), no toda la app.
 */
val LocalArtworkTint = compositionLocalOf<Color?> { null }

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

/** Version publica: ajusta [color] para que contraste con [background] (aclara sobre oscuro, oscurece sobre claro). */
fun contrastingOn(color: Color, background: Color): Color =
    readableAccent(color, background, if (background.luminance() < 0.5f) Color.White else Color.Black)

private fun onColor(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF101014) else Color.White

private fun hue(c: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return hsv[0]
}

// ============================================================================ ESQUEMAS DE COLOR

/** Clasico / Dinamico: integra el selector de color libre. */
private fun classicScheme(dark: Boolean, accent: Color): ColorScheme =
    if (dark) {
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
    } else {
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

/**
 * Minimalista (Niagara): negro profundo o blanco puro. Alto contraste en TODOS los elementos interactivos:
 * los colores que usan Slider (pista inactiva), Switch (pista y borde) y campos de texto (borde) se definen
 * de forma explicita para que se vean sobre el fondo puro.
 */
private fun minimalScheme(dark: Boolean, accent: Color): ColorScheme =
    if (dark) {
        val bg = Color.Black
        val primary = readableAccent(accent, bg, Color.White)
        darkColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = Color(0xFF1F1F1F),
            onPrimaryContainer = Color.White,
            secondary = primary,
            secondaryContainer = Color(0xFF3A3A3A),
            background = bg,
            onBackground = Color.White,
            surface = bg,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1A1A1A),
            onSurfaceVariant = Color(0xFFD0D0D0),
            outline = Color(0xFF9A9A9A),
            outlineVariant = Color(0xFF4A4A4A),
            surfaceContainer = Color(0xFF0A0A0A),
            surfaceContainerHigh = Color(0xFF121212),
            surfaceContainerHighest = Color(0xFF2E2E2E),
        )
    } else {
        val bg = Color.White
        val primary = readableAccent(accent, bg, Color.Black)
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = Color(0xFFEDEDED),
            onPrimaryContainer = Color.Black,
            secondary = primary,
            secondaryContainer = Color(0xFFD0D0D0),
            background = bg,
            onBackground = Color.Black,
            surface = bg,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFEDEDED),
            onSurfaceVariant = Color(0xFF3F3F3F),
            outline = Color(0xFF6A6A6A),
            outlineVariant = Color(0xFFBDBDBD),
            surfaceContainer = Color(0xFFF7F7F7),
            surfaceContainerHigh = Color(0xFFEFEFEF),
            surfaceContainerHighest = Color(0xFFDDDDDD),
        )
    }

/** Pixel / Retro 8-bit: colores saturados "arcade" derivados del matiz elegido. */
private fun pixelScheme(dark: Boolean, accent: Color): ColorScheme {
    val h = hue(accent)
    return if (dark) {
        val bg = Color.hsv(h, 0.70f, 0.12f)
        val primary = Color.hsv(h, 0.90f, 1f) // neon
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF0B0620),
            primaryContainer = Color.hsv(h, 0.80f, 0.35f),
            onPrimaryContainer = Color.White,
            secondary = Color.hsv((h + 120f) % 360f, 0.90f, 1f),
            tertiary = Color.hsv((h + 240f) % 360f, 0.90f, 1f),
            secondaryContainer = Color.hsv(h, 0.55f, 0.40f),
            background = bg,
            onBackground = Color(0xFFF4F0FF),
            surface = Color.hsv(h, 0.65f, 0.17f),
            onSurface = Color(0xFFF4F0FF),
            surfaceVariant = Color.hsv(h, 0.55f, 0.24f),
            onSurfaceVariant = Color(0xFFD8D0F0),
            outline = primary.copy(alpha = 0.8f),
            surfaceContainer = Color.hsv(h, 0.60f, 0.20f),
            surfaceContainerHigh = Color.hsv(h, 0.55f, 0.26f),
            surfaceContainerHighest = Color.hsv(h, 0.50f, 0.34f),
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
            secondaryContainer = Color(0xFFFFD36B),
            background = bg,
            onBackground = ink,
            surface = Color(0xFFFFFBEA),
            onSurface = ink,
            surfaceVariant = Color(0xFFFFE9A8),
            onSurfaceVariant = Color(0xFF4A3F6B),
            outline = ink,
            surfaceContainer = Color(0xFFFFF0BF),
            surfaceContainerHigh = Color(0xFFFFE9A8),
            surfaceContainerHighest = Color(0xFFFFE08A),
        )
    }
}

/** Liquid Glass (inspirado en Apple Music): fondos profundos, cristal translucido y acentos vibrantes. */
private fun liquidScheme(dark: Boolean, accent: Color): ColorScheme {
    val h = hue(accent)
    return if (dark) {
        val bg = Color(0xFF0A0A10)
        val primary = readableAccent(Color.hsv(h, 0.72f, 1f), bg, Color.White)
        darkColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = lerp(bg, primary, 0.35f),
            onPrimaryContainer = Color.White,
            secondary = Color.hsv((h + 40f) % 360f, 0.65f, 1f),
            tertiary = Color.hsv((h + 300f) % 360f, 0.65f, 1f),
            secondaryContainer = Color(0xFF34344A),
            background = bg,
            onBackground = Color(0xFFF5F5FA),
            surface = Color(0xFF14141C),
            onSurface = Color(0xFFF5F5FA),
            surfaceVariant = Color(0xFF24243A),
            onSurfaceVariant = Color(0xFFC4C4D6),
            outline = Color(0xFF9494AC),
            surfaceContainer = Color(0xFF1B1B26),
            surfaceContainerHigh = Color(0xFF262634),
            surfaceContainerHighest = Color(0xFF30304A),
        )
    } else {
        val bg = Color(0xFFF3F3F8)
        val primary = readableAccent(Color.hsv(h, 0.78f, 0.88f), bg, Color.Black)
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = lerp(Color.White, primary, 0.22f),
            onPrimaryContainer = Color(0xFF14141C),
            secondary = Color.hsv((h + 40f) % 360f, 0.70f, 0.85f),
            tertiary = Color.hsv((h + 300f) % 360f, 0.70f, 0.85f),
            secondaryContainer = Color(0xFFD8D8E6),
            background = bg,
            onBackground = Color(0xFF14141C),
            surface = Color.White,
            onSurface = Color(0xFF14141C),
            surfaceVariant = Color(0xFFE4E4EF),
            onSurfaceVariant = Color(0xFF55556A),
            outline = Color(0xFF80809A),
            surfaceContainer = Color(0xFFEDEDF4),
            surfaceContainerHigh = Color(0xFFE6E6F0),
            surfaceContainerHighest = Color(0xFFDCDCEA),
        )
    }
}

private fun schemeFor(style: ThemeStyle, dark: Boolean, accent: Color): ColorScheme = when (style) {
    ThemeStyle.CLASSIC -> classicScheme(dark, accent)
    ThemeStyle.MINIMAL -> minimalScheme(dark, accent)
    ThemeStyle.PIXEL -> pixelScheme(dark, accent)
    ThemeStyle.LIQUID_GLASS -> liquidScheme(dark, accent)
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
    ThemeStyle.LIQUID_GLASS -> FontFamily.SansSerif
}

private fun shapesFor(style: ThemeStyle) = Shapes(
    extraSmall = cornerShape(style, 4.dp),
    small = cornerShape(style, 8.dp),
    medium = cornerShape(style, 12.dp),
    large = cornerShape(style, 16.dp),
    extraLarge = cornerShape(style, 28.dp),
)

/**
 * @param dark ya resuelto (Sistema/Claro/Oscuro): el tema no depende del modo elegido sino del resultado.
 * Esquemas, formas y tipografia se calculan con `remember` y solo se recalculan cuando cambia su clave, asi que
 * cambiar de estilo cuesta un unico calculo y una recomposicion (sin recrear el arbol de pantallas).
 */
@Composable
fun BeroTheme(
    dark: Boolean,
    accent: Color,
    gradient: Boolean = false,
    style: ThemeStyle = ThemeStyle.CLASSIC,
    artworkTint: Color? = null,
    glass: GlassConfig = GlassConfig(),
    content: @Composable () -> Unit,
) {
    val scheme = remember(style, dark, accent) { schemeFor(style, dark, accent) }
    val shapes = remember(style) { shapesFor(style) }
    val family = remember(style) { familyFor(style) }
    val typography = remember(family) { if (family == null) Typography() else Typography().withFontFamily(family) }

    CompositionLocalProvider(
        LocalGradientBackground provides gradient,
        LocalThemeStyle provides style,
        LocalArtworkTint provides artworkTint,
        LocalGlassConfig provides glass,
        // El color de contenido por defecto (texto/iconos sin color explicito) SIEMPRE sigue al tema:
        // sin esto era negro en las pantallas que no estan dentro de un Surface/Scaffold (Ajustes, editor...).
        LocalContentColor provides scheme.onBackground,
    ) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography) {
            // IMPORTANTE: la estructura NO depende del estilo. Antes, `if (family == null) content() else
            // ProvideTextStyle { content() }` cambiaba el grupo de composicion al pasar a/desde Clasico: Compose
            // destruia TODA la app (incluida la pantalla de Ajustes abierta) y la recreaba desde cero.
            ProvideTextStyle(TextStyle(fontFamily = family)) {
                content()
            }
        }
    }
}

/**
 * Fondo de la app:
 *  - Liquid Glass: degradado + resplandor que toman el color de la caratula (se anima al cambiar de cancion
 *    y solo invalida el DIBUJO, nunca recompone);
 *  - resto: color plano o, si el usuario lo activa, degradado sutil con el acento (el Minimalista siempre es puro).
 */
fun Modifier.appBackground(): Modifier = composed {
    val cs = MaterialTheme.colorScheme
    val style = LocalThemeStyle.current
    val dark = cs.background.luminance() < 0.5f

    if (style == ThemeStyle.LIQUID_GLASS) {
        val tint = animateColorAsState(LocalArtworkTint.current ?: cs.primary, tween(900), label = "artworkTint")
        val base = cs.background
        background(base).drawBehind {
            val t = tint.value
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        t.copy(alpha = if (dark) 0.55f else 0.38f),
                        t.copy(alpha = if (dark) 0.18f else 0.12f),
                        Color.Transparent
                    )
                )
            )
            drawRect(
                Brush.radialGradient(
                    listOf(t.copy(alpha = if (dark) 0.35f else 0.25f), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.05f),
                    radius = size.maxDimension * 0.7f
                )
            )
        }
    } else if (LocalGradientBackground.current && style != ThemeStyle.MINIMAL) {
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
