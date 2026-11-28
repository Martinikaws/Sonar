package app.sonar.ui.looks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sonar.SonarApplication
import app.sonar.data.PreferencesStore
import app.sonar.data.PreferencesStore.LooksOption
import app.sonar.ui.components.Artwork
import kotlinx.coroutines.isActive

@Composable
internal fun currentLooks(): PreferencesStore.Looks {
    val context = LocalContext.current
    val preferences = (context.applicationContext as? SonarApplication)?.preferences
        ?: return defaultLooks
    val looks by preferences.looks.collectAsStateWithLifecycle()
    return looks
}

private val defaultLooks = PreferencesStore.Looks(LooksOption.entries.filter { it.default }.toSet())

@Composable
internal fun looksThemeSeed(cover: Color?): Color? {
    return resolveLooksSeed(currentLooks(), cover)
}

internal fun resolveLooksSeed(looks: PreferencesStore.Looks, cover: Color?): Color? =
    if (LooksOption.CoverColors in looks) cover
    else looksAccentArgb(looks.accent)?.let { Color(it) }

internal fun looksAccentArgb(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return hex.toInt(16) or 0xFF000000.toInt()
}

@Composable
internal fun SpinningCover(
    url: String,
    title: String,
    playing: Boolean,
    modifier: Modifier = Modifier.padding(24.dp).aspectRatio(1f),
) {
    Artwork(
        url = url,
        title = title,
        modifier = modifier.spinningArtwork(url, playing).clip(CircleShape),
        corner = 0.dp,
    )
}

private const val COVER_REVOLUTION_MS = 18_000

@Composable
private fun Modifier.spinningArtwork(url: String, playing: Boolean): Modifier {
    val angle = remember(url) { Animatable(0f) }
    LaunchedEffect(angle, playing) {
        if (!playing) return@LaunchedEffect
        while (isActive) {
            val remaining = 1f - angle.value / 360f
            val duration = (remaining * COVER_REVOLUTION_MS).toInt().coerceAtLeast(1)
            angle.animateTo(360f, tween(duration, easing = LinearEasing))
            angle.snapTo(0f)
        }
    }
    return graphicsLayer { rotationZ = angle.value }
}
