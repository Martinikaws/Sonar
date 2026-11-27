package app.sonar.ui.look

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.sin

private val anchors = listOf(
    Offset(0.20f, 0.25f), Offset(0.80f, 0.20f),
    Offset(0.75f, 0.80f), Offset(0.25f, 0.75f),
)
private val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)

/** A finite drift on track or preset changes, with the same resting mesh in reduced motion. */
@Composable
fun LookBackdrop(
    look: Look,
    modifier: Modifier = Modifier,
    trackKey: Any? = null,
    reducedMotion: Boolean = rememberLookReducedMotion(),
    animate: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = when (look) {
        Look.CLASSIC -> return
        Look.SPOTIFUSION -> listOf(scheme.primary, scheme.surface, scheme.surface, scheme.secondary)
        Look.IMMERSIVE -> listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primary)
        Look.GLASSIFY -> listOf(scheme.secondary, scheme.surface, scheme.primary, scheme.secondary)
        Look.LIQUID_MUSIC -> listOf(scheme.surface, scheme.tertiary, scheme.surface, scheme.tertiary)
        Look.STARRY_NIGHT -> listOf(scheme.secondary, scheme.primary, scheme.tertiary, scheme.surface)
        Look.CUPERTINO -> listOf(scheme.tertiary, scheme.surface, scheme.tertiary, scheme.primary)
        Look.SQUARED -> listOf(scheme.primary, scheme.secondary, scheme.primary, scheme.tertiary)
    }
    val strength = when (look) {
        Look.SPOTIFUSION, Look.LIQUID_MUSIC -> 0.18f
        Look.GLASSIFY, Look.CUPERTINO -> 0.28f
        else -> 0.42f
    }
    val drift = remember(look, trackKey, reducedMotion) {
        Animatable(if (reducedMotion) 0f else 1f)
    }
    LaunchedEffect(drift, animate) {
        if (animate && drift.value > 0f) {
            drift.animateTo(0f, tween(durationMillis = (8_000 * drift.value).toInt()))
        }
    }
    Box(modifier.fillMaxSize().drawWithCache {
        val radius = (size.maxDimension * 0.78f).coerceAtLeast(1f)
        val brushes = palette.map { color ->
            Brush.radialGradient(
                0f to color.copy(alpha = strength),
                0.38f to color.copy(alpha = strength * 0.65f),
                1f to color.copy(alpha = 0f),
                center = Offset.Zero,
                radius = radius,
            )
        }
        val scrim = Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.78f)),
        )
        onDrawBehind {
            val phase = if (reducedMotion) 0f else drift.value
            for (index in anchors.indices) {
                val anchor = anchors[index]
                val x = (anchor.x + 0.16f * cos(phase * speeds[index] + index * 1.7f)) * size.width
                val y = (anchor.y + 0.16f * sin(phase * speeds[index] * 0.9f + index * 2.3f)) * size.height
                translate(x, y) {
                    drawCircle(brushes[index], radius = radius, center = Offset.Zero)
                }
            }
            drawRect(scrim)
        }
    })
}
