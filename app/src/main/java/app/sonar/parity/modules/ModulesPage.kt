package app.sonar.parity.modules

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sonar.R
import app.sonar.ui.look.rememberLookReducedMotion
import coil.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val MODULES_ROUTE = "modules"

private enum class Mode(val label: Int) {
    Artwork(R.string.modules_artwork),
    Ncs(R.string.modules_ncs),
    Spectrum(R.string.modules_spectrum),
    Waveform(R.string.modules_waveform),
}

@Composable
internal fun ModulesPage(
    artwork: String?,
    title: String,
    playing: Boolean,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(Mode.Artwork) }
    var frame by remember { mutableStateOf<AudioFrame?>(null) }
    var waitingForAudio by remember { mutableStateOf(true) }
    var artworkStatus by remember(artwork) { mutableStateOf<Int?>(null) }
    val reducedMotion = rememberLookReducedMotion()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, playing, reducedMotion) {
        frame = null
        if (playing && !reducedMotion) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                waitingForAudio = true
                val startup = launch {
                    delay(2_000)
                    waitingForAudio = false
                }
                try {
                    val initial = AudioFrames.frames.value
                    AudioFrames.frames.collectLatest { incoming ->
                        if (incoming != null && incoming !== initial) {
                            startup.cancel()
                            waitingForAudio = false
                            frame = incoming
                            delay(300)
                            frame = null
                        }
                    }
                } finally {
                    frame = null
                }
            }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.modules_back))
            }
            Text(stringResource(R.string.modules_title), style = MaterialTheme.typography.headlineSmall)
        }
        Mode.entries.chunked(2).forEach { pair ->
            Row {
                pair.forEach { option ->
                    TextButton(
                        onClick = { mode = option },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        enabled = mode != option,
                    ) { Text(stringResource(option.label)) }
                }
            }
        }

        val activeFrame = frame
        when {
            mode == Mode.Artwork && artwork != null -> {
                val pulse = 1f + (activeFrame?.beatPulse ?: 0f) * 0.12f
                AsyncImage(
                    model = artwork,
                    contentDescription = title.ifBlank { stringResource(R.string.modules_artwork) },
                    contentScale = ContentScale.Fit,
                    onLoading = { artworkStatus = R.string.modules_artwork_loading },
                    onSuccess = { artworkStatus = null },
                    onError = { artworkStatus = R.string.modules_artwork_error },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    },
                )
            }
            mode != Mode.Artwork && activeFrame != null -> FrameView(mode, activeFrame)
        }
        val notice = when {
            mode == Mode.Artwork && artwork == null -> R.string.modules_no_artwork
            mode == Mode.Artwork && artworkStatus != null -> artworkStatus
            reducedMotion -> R.string.modules_motion_disabled
            !playing -> R.string.modules_idle
            activeFrame == null && waitingForAudio -> R.string.modulereview_waiting_audio
            activeFrame == null -> R.string.modules_no_audio
            else -> null
        }
        notice?.let {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FrameView(mode: Mode, frame: AudioFrame) {
    val ink = MaterialTheme.colorScheme.primary
    val label = stringResource(mode.label)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics {
        role = Role.Image
        contentDescription = label
    }) {
        when (mode) {
            Mode.Artwork -> Unit
            Mode.Spectrum -> {
                val step = size.width / frame.spectrum.size
                frame.spectrum.forEachIndexed { index, level ->
                    val height = sqrtLevel(level) * size.height
                    drawRect(ink, Offset(index * step, size.height - height), Size(step * 0.8f, height))
                }
            }
            Mode.Waveform -> {
                val path = Path()
                frame.samples.forEachIndexed { index, sample ->
                    val x = index * size.width / (frame.samples.size - 1)
                    val y = center.y - sample * center.y
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, ink, style = Stroke(width = 2.dp.toPx()))
            }
            Mode.Ncs -> {
                for (point in spherePoints) {
                    val band = ((point.y + 1f) * 0.5f * frame.spectrum.lastIndex).toInt()
                    val energy = sqrtLevel(frame.spectrum[band])
                    val radius = size.minDimension * (0.30f + 0.08f * energy + 0.04f * frame.beatPulse)
                    val perspective = 1f / (1f - point.z * 0.18f)
                    drawCircle(
                        color = ink.copy(alpha = 0.25f + 0.65f * (point.z + 1f) * 0.5f),
                        radius = size.minDimension * (0.003f + 0.004f * energy) * perspective,
                        center = center + Offset(point.x, point.y) * (radius * perspective),
                    )
                }
            }
        }
    }
}

private data class SpherePoint(val x: Float, val y: Float, val z: Float)

// The golden angle spaces the particles evenly over the surface, including near the poles.
private val spherePoints = List(420) { index ->
    val y = 1f - 2f * (index + 0.5f) / 420
    val radius = sqrtLevel(1f - y * y)
    val angle = PI * (3 - kotlin.math.sqrt(5.0)) * index
    SpherePoint(radius * cos(angle).toFloat(), y, radius * sin(angle).toFloat())
}.sortedBy { it.z }

private fun sqrtLevel(value: Float): Float = kotlin.math.sqrt(value)
