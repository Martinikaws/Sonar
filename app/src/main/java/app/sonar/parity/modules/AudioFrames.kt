package app.sonar.parity.modules

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class AudioFrame(
    val samples: FloatArray,
    val spectrum: FloatArray,
    val level: Float,
    val beatPulse: Float = 0f,
)

internal object AudioFrames {
    internal const val WINDOW = 256
    private val latest = MutableStateFlow<AudioFrame?>(null)
    val frames = latest.asStateFlow()
    private val pcm = PcmWindow()
    private val beats = BeatDetector()
    private var sampledAt = -50L
    private var offeredAt = 0L
    private var previousChannels = 0

    @Synchronized
    fun offer(count: Int, channels: Int, sample: (Int) -> Float) {
        if (channels <= 0 || count < channels) return
        val now = SystemClock.uptimeMillis()
        val subscribed = latest.subscriptionCount.value > 0
        if (!subscribed || channels != previousChannels || now - offeredAt >= 300) {
            pcm.reset()
            beats.reset()
        }
        previousChannels = channels
        offeredAt = now
        if (!subscribed || now - sampledAt < 50) return
        val samples = pcm.append(count, channels, sample) ?: return
        sampledAt = now
        val frame = analyzeFrame(samples)
        latest.value = frame.copy(beatPulse = beats.update(frame.level, now))
    }
}

internal class PcmWindow {
    private var samples = FloatArray(AudioFrames.WINDOW)
    private var filled = 0

    fun reset() {
        filled = 0
    }

    fun append(count: Int, channels: Int, sample: (Int) -> Float): FloatArray? {
        val frames = minOf(count / channels, samples.size - filled)
        for (frame in 0 until frames) {
            val start = frame * channels
            var total = 0f
            for (channel in 0 until channels) total += sample(start + channel)
            samples[filled++] = (total / channels).coerceIn(-1f, 1f)
        }
        if (filled < samples.size) return null
        val complete = samples
        samples = FloatArray(AudioFrames.WINDOW)
        reset()
        return complete
    }
}

internal class BeatDetector {
    private var average = 0f
    private var previous = 0f
    private var onsetAt = -250L

    fun reset() {
        average = 0f
        previous = 0f
        onsetAt = -250L
    }

    fun update(level: Float, now: Long): Float {
        if (now - onsetAt >= 250 && level - previous > 0.04f && level > average * 1.35f) {
            onsetAt = now
        }
        average += (level - average) * 0.2f
        previous = level
        return (1f - (now - onsetAt) / 200f).coerceIn(0f, 1f)
    }
}

private val window = DoubleArray(AudioFrames.WINDOW) { index ->
    0.5 - 0.5 * cos(2 * PI * index / (AudioFrames.WINDOW - 1))
}
private val frequencies = listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 14, 20, 28, 40, 56, 80, 112).map { bin ->
    val angle = 2 * PI * bin / AudioFrames.WINDOW
    Pair(cos(angle), sin(angle))
}

internal fun analyzeFrame(samples: FloatArray): AudioFrame {
    require(samples.size == AudioFrames.WINDOW)
    val weighted = DoubleArray(samples.size) { samples[it] * window[it] }
    val spectrum = FloatArray(frequencies.size) { band ->
        val (cosine, sine) = frequencies[band]
        var previous = 0.0
        var beforePrevious = 0.0
        for (value in weighted) {
            val next = value + 2 * cosine * previous - beforePrevious
            beforePrevious = previous
            previous = next
        }
        val real = previous - beforePrevious * cosine
        val imaginary = beforePrevious * sine
        (4 * sqrt(real * real + imaginary * imaginary) / samples.size).toFloat().coerceIn(0f, 1f)
    }
    val level = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
    return AudioFrame(samples, spectrum, level)
}
