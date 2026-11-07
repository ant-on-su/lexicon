package com.example.lexicon

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lexicon.ui.theme.Ink
import com.example.lexicon.ui.theme.Muted
import com.example.lexicon.ui.theme.Pastels
import kotlin.math.sin
import kotlin.random.Random

/**
 * A chunky rounded face on a darker base. It presses down while touched and springs back.
 * Give it a size through [modifier].
 */
@Composable
fun Chunky(
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(28.dp),
    depth: Dp = 6.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateDpAsState(
        targetValue = if (pressed) depth else 0.dp,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    Box(modifier.clickable(interaction, indication = null, enabled = enabled, onClick = onClick)) {
        Box(
            Modifier
                .matchParentSize()
                .padding(top = depth)
                .background(lerp(color, Color.Black, 0.18f), shape)
        )
        Box(
            Modifier
                .matchParentSize()
                .padding(bottom = depth)
                .offset { IntOffset(0, press.roundToPx()) }
                .background(color, shape),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
fun ChunkyButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
) {
    Chunky(color, onClick, modifier.fillMaxWidth().height(96.dp), enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(AnnotatedString(text), maxSize = 32.sp, maxLines = 1, color = if (enabled) Ink else Muted)
            if (note != null) FitText(AnnotatedString(note), maxSize = 14.sp, maxLines = 1, color = Muted)
        }
    }
}

/** Shrinks the text to fit before cutting it off. */
@Composable
fun FitText(
    text: AnnotatedString,
    maxSize: TextUnit,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 10.sp,
    maxLines: Int = 2,
    color: Color = Ink,
    weight: FontWeight = FontWeight.Black,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontWeight = weight, textAlign = TextAlign.Center),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 1.sp),
    )
}

private class Bit(random: Random) {
    val x = random.nextFloat()
    val delay = random.nextFloat() * 0.3f
    val speed = 0.9f + random.nextFloat() * 0.6f
    val sway = 0.02f + random.nextFloat() * 0.05f
    val phase = random.nextFloat() * 6f
    val spin = (random.nextFloat() - 0.5f) * 1440f
    val color = Pastels[random.nextInt(Pastels.size)]
}

/** Paper confetti falling once over the whole screen. */
@Composable
fun Confetti(modifier: Modifier = Modifier, pieces: Int = 120) {
    val progress = remember { Animatable(0f) }
    val bits = remember { List(pieces) { Bit(Random) } }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(3500, easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val piece = Size(10.dp.toPx(), 5.dp.toPx())
        bits.forEach { bit ->
            val p = ((t - bit.delay) / (1f - bit.delay)).coerceIn(0f, 1f)
            val x = (bit.x + bit.sway * sin(p * 12f + bit.phase)) * size.width
            val y = (-0.05f + p * bit.speed * 1.1f) * size.height
            rotate(bit.spin * p, pivot = Offset(x, y)) {
                drawRect(
                    color = lerp(bit.color, Color.Black, 0.1f),
                    topLeft = Offset(x - piece.width / 2, y - piece.height / 2),
                    size = piece,
                    alpha = if (t < 0.8f) 1f else (1f - t) / 0.2f,
                )
            }
        }
    }
}

/** "45.3 s" or "1:05.3". */
fun formatTime(ms: Long): String {
    val tenths = ms / 100
    val seconds = tenths / 10
    return if (seconds >= 60) {
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}.${tenths % 10}"
    } else {
        "$seconds.${tenths % 10} s"
    }
}
