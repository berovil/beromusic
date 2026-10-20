package com.example.bero.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.bero.data.VisualizerStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.example.bero.ui.theme.beroShape
import com.example.bero.ui.theme.beroWeight

/**
 * Cinco estilos del espectrograma. Todos usan el MISMO array de valores 0..1, asi que alternar entre
 * ellos es gratis (solo cambia la funcion de dibujo, no la captura).
 */
/** Objetos reutilizables entre frames: el estilo "Onda" dibuja a 60 fps sin crear Path ni Brush nuevos. */
class SpectrumScratch {
    val line = Path()
    val fill = Path()
    var brush: Brush? = null
    var brushHeight = -1f
    var brushColor = Color.Unspecified
}

fun DrawScope.drawSpectrum(
    style: VisualizerStyle,
    values: FloatArray,
    peaks: FloatArray,
    color: Color,
    scratch: SpectrumScratch,
) {
    val n = values.size
    if (n == 0) return
    val w = size.width
    val h = size.height
    val minH = 4.dp.toPx()

    when (style) {
        VisualizerStyle.BARS -> {
            val gap = 4.dp.toPx()
            val bw = (w - gap * (n - 1)) / n
            for (i in 0 until n) {
                val v = values[i]
                val bh = max(minH, v * h)
                drawRoundRect(
                    color = color.copy(alpha = 0.55f + 0.40f * v),
                    topLeft = Offset(i * (bw + gap), h - bh),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bw / 2f)
                )
            }
        }

        VisualizerStyle.WAVE -> {
            val step = w / (n - 1).coerceAtLeast(1)
            val line = scratch.line
            line.rewind()
            var px = 0f
            var py = h - max(minH, values[0] * h)
            line.moveTo(px, py)
            for (i in 1 until n) {
                val x = i * step
                val y = h - max(minH, values[i] * h)
                line.quadraticTo(px, py, (px + x) / 2f, (py + y) / 2f)
                px = x
                py = y
            }
            line.lineTo(px, py)

            val fill = scratch.fill
            fill.rewind()
            fill.addPath(line)
            fill.lineTo(w, h)
            fill.lineTo(0f, h)
            fill.close()
            var brush = scratch.brush
            if (brush == null || scratch.brushHeight != h || scratch.brushColor != color) {
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.50f), color.copy(alpha = 0f)), 0f, h)
                scratch.brush = brush
                scratch.brushHeight = h
                scratch.brushColor = color
            }
            drawPath(fill, brush)
            drawPath(
                line,
                color.copy(alpha = 0.95f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        VisualizerStyle.DOTS -> {
            val rows = 5
            val slot = w / n
            val rowH = h / rows
            val r = min(slot * 0.32f, rowH * 0.42f)
            for (i in 0 until n) {
                val lit = ceil(values[i] * rows).toInt().coerceIn(0, rows)
                val cx = i * slot + slot / 2f
                for (row in 0 until rows) {
                    val cy = h - (row + 0.5f) * rowH
                    drawCircle(
                        color = color.copy(alpha = if (row < lit) 0.55f + 0.40f * values[i] else 0.12f),
                        radius = r,
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        VisualizerStyle.MIRROR -> {
            val gap = 4.dp.toPx()
            val bw = (w - gap * (n - 1)) / n
            for (i in 0 until n) {
                val v = values[i]
                val bh = max(minH, v * h)
                drawRoundRect(
                    color = color.copy(alpha = 0.50f + 0.45f * v),
                    topLeft = Offset(i * (bw + gap), (h - bh) / 2f),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bw / 2f)
                )
            }
        }

        VisualizerStyle.PEAKS -> {
            val slot = w / n
            val bw = slot * 0.42f
            val cap = 3.dp.toPx()
            for (i in 0 until n) {
                val v = values[i]
                val bh = max(minH, v * h)
                val x = i * slot + (slot - bw) / 2f
                drawRoundRect(
                    color = color.copy(alpha = 0.45f + 0.40f * v),
                    topLeft = Offset(x, h - bh),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bw / 2f)
                )
                val capY = (h - max(minH, peaks.getOrElse(i) { v } * h) - cap - 2.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(
                    color = color.copy(alpha = 0.95f),
                    topLeft = Offset(x, capY),
                    size = Size(bw, cap),
                    cornerRadius = CornerRadius(cap / 2f)
                )
            }
        }
    }
}

/** Boton "ajustes del visualizador": elegir uno de los 5 estilos con vista previa. */
@Composable
fun VisualizerStyleDialog(
    current: VisualizerStyle,
    onSelect: (VisualizerStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = beroShape(28.dp)
    val sample = remember { FloatArray(32) { i -> (0.2f + 0.7f * abs(sin(i * 0.42f + 0.6f))).coerceIn(0f, 1f) } }
    val samplePeaks = remember { FloatArray(32) { i -> (sample[i] + 0.12f).coerceAtMost(1f) } }
    val accent = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("Estilo del espectrograma", fontSize = 20.sp, fontWeight = beroWeight(FontWeight.Bold))
            Spacer(Modifier.height(12.dp))

            VisualizerStyle.entries.forEach { style ->
                val selected = style == current
                val scratch = remember { SpectrumScratch() }
                val rowShape = beroShape(18.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(rowShape)
                        .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
                        .border(1.dp, if (selected) accent else Color.Transparent, rowShape)
                        .pressable(rowShape, 0.97f) {
                            onSelect(style)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        style.label,
                        fontWeight = if (selected) beroWeight(FontWeight.Bold) else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurface
                    )
                    Canvas(
                        Modifier
                            .width(110.dp)
                            .size(width = 110.dp, height = 40.dp)
                    ) {
                        drawSpectrum(style, sample, samplePeaks, accent, scratch)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        }
    }
}
