package com.example.bero.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import com.example.bero.ui.theme.beroShape
import com.example.bero.ui.theme.beroCircle
import com.example.bero.ui.theme.beroWeight

/**
 * Selector de color libre: circulo cromatico (matiz = angulo, saturacion = radio) + deslizador de brillo.
 * Devuelve cualquier color; el tema se encarga de garantizar contraste (ver BeroTheme).
 */
@Composable
fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onConfirm: (Color) -> Unit) {
    val start = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2].coerceAtLeast(0.25f)) }
    val color = Color.hsv(hue, sat, value)
    val shape = beroShape(28.dp)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Elige tu color", fontSize = 20.sp, fontWeight = beroWeight(FontWeight.Bold))
            Spacer(Modifier.height(16.dp))

            ColorWheel(
                hue = hue,
                saturation = sat,
                value = value,
                onPick = { h, s ->
                    hue = h
                    sat = s
                },
                modifier = Modifier.size(240.dp)
            )

            Spacer(Modifier.height(12.dp))
            Text("Brillo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = value, onValueChange = { value = it }, valueRange = 0.25f..1f)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(beroCircle())
                        .background(color)
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), beroCircle())
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "#%06X".format(color.toArgb() and 0xFFFFFF),
                    fontWeight = beroWeight(FontWeight.SemiBold),
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                TextButton(onClick = { onConfirm(color) }) { Text("Aplicar") }
            }
        }
    }
}

@Composable
private fun ColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onPick: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestPick by rememberUpdatedState(onPick)
    val sweep = remember { Brush.sweepGradient(List(7) { Color.hsv(it * 60f, 1f, 1f) }) }

    fun pick(p: Offset, size: IntSize) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dx = p.x - cx
        val dy = p.y - cy
        var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (angle < 0f) angle += 360f
        latestPick(angle, (hypot(dx, dy) / min(cx, cy)).coerceIn(0f, 1f))
    }

    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { pick(it, size) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pick(it, size) },
                    onDrag = { change, _ ->
                        change.consume()
                        pick(change.position, size)
                    }
                )
            }
    ) {
        val r = min(size.width, size.height) / 2f
        drawCircle(brush = sweep, radius = r)
        // saturacion: blanco en el centro que se desvanece hacia el borde
        drawCircle(brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r), radius = r)
        // brillo
        drawCircle(color = Color.Black.copy(alpha = 1f - value), radius = r)

        val rad = Math.toRadians(hue.toDouble())
        val sel = Offset(
            center.x + (cos(rad) * saturation * r).toFloat(),
            center.y + (sin(rad) * saturation * r).toFloat()
        )
        drawCircle(Color.hsv(hue, saturation, value), radius = 11.dp.toPx(), center = sel)
        drawCircle(Color.White, radius = 11.dp.toPx(), center = sel, style = Stroke(width = 3.dp.toPx()))
    }
}
