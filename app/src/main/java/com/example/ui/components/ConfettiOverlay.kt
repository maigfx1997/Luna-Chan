package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.example.ui.theme.ConfettiGold
import com.example.ui.theme.Pinky100
import com.example.ui.theme.Pinky300
import com.example.ui.theme.Pinky500
import com.example.ui.theme.Pinky700
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class ConfettiParticle(
    val angleRad: Float,
    val speed: Float,
    val spinSpeed: Float,
    val color: Color,
    val widthDp: Float,
    val heightDp: Float,
    val isCircle: Boolean
)

@Composable
fun ConfettiOverlay(
    trigger: Int,
    modifier: Modifier = Modifier
) {
    if (trigger <= 0) return

    val palette = remember {
        listOf(Pinky500, Pinky300, Pinky100, Pinky700, ConfettiGold, Color.White)
    }

    val particles = remember(trigger) {
        val rng = Random(trigger * 9973)
        List(72) {
            val spreadDeg = -135f + rng.nextFloat() * 90f
            ConfettiParticle(
                angleRad = Math.toRadians(spreadDeg.toDouble()).toFloat(),
                speed = 0.45f + rng.nextFloat() * 0.95f,
                spinSpeed = (rng.nextFloat() - 0.5f) * 720f,
                color = palette[rng.nextInt(palette.size)],
                widthDp = 6f + rng.nextFloat() * 6f,
                heightDp = 4f + rng.nextFloat() * 8f,
                isCircle = rng.nextFloat() > 0.72f
            )
        }
    }

    val progress = remember(trigger) { Animatable(0f) }

    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1900, easing = LinearEasing)
        )
    }

    val t = progress.value
    if (t >= 0.99f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val originX = size.width * 0.5f
        val originY = size.height * 0.62f
        val maxDist = size.minDimension * 0.85f
        val gravity = size.height * 0.75f
        val alpha = (1f - ((t - 0.6f).coerceAtLeast(0f) / 0.4f)).coerceIn(0f, 1f)

        particles.forEach { p ->
            val vx = cos(p.angleRad) * p.speed * maxDist
            val vy = sin(p.angleRad) * p.speed * maxDist
            val x = originX + vx * t
            val y = originY + vy * t + 0.5f * gravity * t * t
            val drawColor = p.color.copy(alpha = alpha)

            if (p.isCircle) {
                drawCircle(
                    color = drawColor,
                    radius = (p.widthDp * 0.5f) * density,
                    center = Offset(x, y)
                )
            } else {
                rotate(degrees = p.spinSpeed * t, pivot = Offset(x, y)) {
                    drawRect(
                        color = drawColor,
                        topLeft = Offset(x - p.widthDp * density / 2f, y - p.heightDp * density / 2f),
                        size = Size(p.widthDp * density, p.heightDp * density)
                    )
                }
            }
        }
    }
}
