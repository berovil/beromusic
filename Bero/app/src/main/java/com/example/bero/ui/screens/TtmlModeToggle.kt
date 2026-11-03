package com.example.bero.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.bero.ui.components.pressable

/**
 * Interruptor de letra TTML: micrófono con brillos. Activo = se muestra la letra TTML (los brillos titilan solo
 * mientras esta activo, asi no hay animacion en reposo); pulsar de nuevo la desactiva y vuelve el LRC.
 */
@Composable
fun TtmlModeToggle(
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content = LocalContentColor.current
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.28f) else content.copy(alpha = 0.10f))
            .border(1.dp, if (active) accent.copy(alpha = 0.75f) else content.copy(alpha = 0.22f), CircleShape)
            .pressable(CircleShape, 0.92f, onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Mic,
            contentDescription = if (active) "Desactivar letra TTML" else "Activar letra TTML",
            tint = if (active) Color.White else content.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        if (active) Sparkles()
    }
}

@Composable
private fun BoxScope.Sparkles() {
    val t = rememberInfiniteTransition(label = "ttmlSparkles")
    val a = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sparkleA")
    val b = t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "sparkleB")
    val gold = Color(0xFFFFE9A8)
    Icon(
        Icons.Rounded.AutoAwesome, null, tint = gold,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 4.dp, end = 4.dp)
            .size(13.dp)
            .graphicsLayer { alpha = a.value; scaleX = 0.7f + 0.3f * a.value; scaleY = 0.7f + 0.3f * a.value }
    )
    Icon(
        Icons.Rounded.AutoAwesome, null, tint = gold,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(bottom = 5.dp, start = 5.dp)
            .size(9.dp)
            .graphicsLayer { alpha = b.value; scaleX = 0.7f + 0.3f * b.value; scaleY = 0.7f + 0.3f * b.value }
    )
}
