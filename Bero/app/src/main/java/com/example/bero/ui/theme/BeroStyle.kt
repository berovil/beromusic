package com.example.bero.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bero.data.GlassQuality
import com.example.bero.data.ThemeStyle

/** Configuracion del cristal. [film] 0..1 (0.5 = original); [frostDp] solo se usa con calidad Calidad. */
data class GlassConfig(
    val quality: GlassQuality = GlassQuality.BALANCED,
    val film: Float = 0.5f,
    val frostDp: Int = 20,
) {
    /** Calidad realmente aplicable: el desenfoque real (RenderEffect) existe desde Android 12 (API 31). */
    val effectiveQuality: GlassQuality
        get() = if (quality == GlassQuality.QUALITY && android.os.Build.VERSION.SDK_INT < 31) {
            GlassQuality.BALANCED
        } else quality
}

val LocalGlassConfig = staticCompositionLocalOf { GlassConfig() }

/** Estilo estetico activo. Cambia pocas veces => CompositionLocal estatico (lecturas sin coste de seguimiento). */
val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.CLASSIC }

/**
 * Forma de un contenedor de radio base [radius], segun el estilo. Devuelve CornerBasedShape (lo exige Material3
 * en `Shapes`): en Pixel, un radio minimo es un CutCornerShape de 0 dp, es decir, un rectangulo.
 */
fun cornerShape(style: ThemeStyle, radius: Dp): CornerBasedShape = when (style) {
    ThemeStyle.CLASSIC -> RoundedCornerShape(radius)
    ThemeStyle.MINIMAL -> RoundedCornerShape(radius * 0.5f) // lineas simples, esquinas discretas
    ThemeStyle.PIXEL -> CutCornerShape(if (radius <= 4.dp) 0.dp else minOf(radius * 0.4f, 8.dp))
    ThemeStyle.LIQUID_GLASS -> RoundedCornerShape(radius * 1.25f) // cristal: curvas amplias y continuas
}

fun styledShape(style: ThemeStyle, radius: Dp): Shape = cornerShape(style, radius)

@Composable
@ReadOnlyComposable
fun beroShape(radius: Dp): Shape = styledShape(LocalThemeStyle.current, radius)

/** Sustituto de CircleShape: en Pixel los botones "redondos" pasan a ser cuadrados con esquinas cortadas. */
@Composable
@ReadOnlyComposable
fun beroCircle(): Shape = when (LocalThemeStyle.current) {
    ThemeStyle.PIXEL -> CutCornerShape(6.dp)
    else -> CircleShape
}

@Composable
@ReadOnlyComposable
fun beroShapeTop(radius: Dp): Shape = when (LocalThemeStyle.current) {
    ThemeStyle.CLASSIC -> RoundedCornerShape(topStart = radius, topEnd = radius)
    ThemeStyle.MINIMAL -> RectangleShape
    ThemeStyle.PIXEL -> CutCornerShape(topStart = minOf(radius * 0.4f, 8.dp), topEnd = minOf(radius * 0.4f, 8.dp))
    ThemeStyle.LIQUID_GLASS -> RoundedCornerShape(topStart = radius * 1.25f, topEnd = radius * 1.25f)
}

@Composable
@ReadOnlyComposable
fun beroShapeBottom(radius: Dp): Shape = when (LocalThemeStyle.current) {
    ThemeStyle.CLASSIC -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    ThemeStyle.MINIMAL -> RectangleShape
    ThemeStyle.PIXEL -> CutCornerShape(bottomStart = minOf(radius * 0.4f, 8.dp), bottomEnd = minOf(radius * 0.4f, 8.dp))
    ThemeStyle.LIQUID_GLASS -> RoundedCornerShape(bottomStart = radius * 1.25f, bottomEnd = radius * 1.25f)
}

/** Minimalista: titulos finos y limpios. Pixel y Liquid Glass: el peso mas extremo se modera. */
@Composable
@ReadOnlyComposable
fun beroWeight(base: FontWeight): FontWeight = when (LocalThemeStyle.current) {
    ThemeStyle.MINIMAL -> when {
        base.weight >= 800 -> FontWeight.Light
        base.weight >= 600 -> FontWeight.Normal
        else -> base
    }

    ThemeStyle.PIXEL, ThemeStyle.LIQUID_GLASS -> if (base.weight >= 800) FontWeight.Bold else base
    ThemeStyle.CLASSIC -> base
}

/**
 * Superficie (tarjeta) segun el estilo:
 *  - Clasico: translucida con borde fino;
 *  - Minimalista: SIN tarjeta, borde ni sombra (solo se rellena con el fondo si [solidWhenMinimal], p. ej. barras fijas);
 *  - Pixel: color solido y borde grueso ortogonal;
 *  - Liquid Glass: cristal esmerilado — relleno translucido con degradado de luz, borde brillante de 1 dp y sombra
 *    suave. (Compose no puede difuminar lo que hay DETRAS de un elemento, asi que el efecto se consigue con capas
 *    translucidas sobre el degradado que toma el color de la caratula.) Con [solidWhenMinimal] las barras fijas
 *    llevan ademas una base opaca para que la lista que pasa por debajo no moleste.
 */
@Composable
fun Modifier.beroSurface(
    shape: Shape,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    alpha: Float = 0.7f,
    bordered: Boolean = true,
    solidWhenMinimal: Boolean = false,
    elevation: Dp = 10.dp,
): Modifier {
    val cs = MaterialTheme.colorScheme
    return when (LocalThemeStyle.current) {
        ThemeStyle.CLASSIC -> {
            val base = this
                .clip(shape)
                .background(color.copy(alpha = alpha))
            if (bordered) base.border(1.dp, cs.onSurface.copy(alpha = 0.08f), shape) else base
        }

        ThemeStyle.MINIMAL -> if (solidWhenMinimal) this.background(cs.background) else this

        ThemeStyle.PIXEL -> this
            .clip(shape)
            .background(color)
            .border(3.dp, if (bordered) cs.primary else cs.outline, shape)

        ThemeStyle.LIQUID_GLASS -> {
            val glass = LocalGlassConfig.current
            val dark = cs.background.luminance() < 0.5f
            // film 0.5 = aspecto original; 0 = mas transparente; 1 = mas opaco.
            val k = 0.5f + glass.film
            val fill = remember(dark, glass.film) {
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = ((if (dark) 0.16f else 0.72f) * k).coerceIn(0f, 0.95f)),
                        Color.White.copy(alpha = ((if (dark) 0.06f else 0.42f) * k).coerceIn(0f, 0.95f))
                    )
                )
            }
            val edge = remember(dark) {
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.42f else 0.95f),
                        Color.White.copy(alpha = if (dark) 0.08f else 0.30f)
                    )
                )
            }
            // Rapido: sin sombra (la sombra con sombreado difuso es lo mas caro del cristal en listas largas).
            val lifted = if (glass.quality == GlassQuality.FAST) {
                this.clip(shape)
            } else {
                // shadow(clip = true) ya recorta al shape: el .clip(shape) que iba detras era una segunda capa de recorte.
                this.shadow(
                    elevation = 14.dp,
                    shape = shape,
                    clip = true,
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                    spotColor = Color.Black.copy(alpha = 0.25f)
                )
            }
            (if (solidWhenMinimal) lifted.background(cs.background.copy(alpha = 0.86f)) else lifted)
                .background(fill)
                .border(1.dp, edge, shape)
        }
    }
}

@Composable
fun Modifier.beroSurface(
    radius: Dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    alpha: Float = 0.7f,
    bordered: Boolean = true,
    solidWhenMinimal: Boolean = false,
    elevation: Dp = 10.dp,
): Modifier = beroSurface(beroShape(radius), color, alpha, bordered, solidWhenMinimal, elevation)
