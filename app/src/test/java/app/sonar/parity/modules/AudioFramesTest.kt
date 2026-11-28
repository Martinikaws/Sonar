package app.sonar.parity.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioFramesTest {
    @Test
    fun shortStereoCallbacksCompleteOneWindowInOrder() {
        val window = PcmWindow()
        val expected = FloatArray(AudioFrames.WINDOW) { it / AudioFrames.WINDOW.toFloat() }
        fun offer(start: Int, frames: Int): FloatArray? = window.append(frames * 2, 2) { sample ->
            val offset = if (sample % 2 == 0) 0.25f else -0.25f
            expected[start + sample / 2] + offset
        }

        assertNull(offer(0, 64))
        assertNull(offer(64, 96))
        assertArrayEquals(expected, offer(160, 96), 0f)
        assertNull(offer(0, 32))
    }

    @Test
    fun onsetPulsesDecayDuringSustainedSoundAndRetriggerOnTheNextBeat() {
        val detector = BeatDetector()
        assertEquals(0f, detector.update(0.02f, 0), 0f)
        assertEquals(1f, detector.update(0.6f, 50), 0f)
        assertEquals(0.5f, detector.update(0.6f, 150), 0f)
        for (time in 250L..650L step 50) {
            assertEquals(0f, detector.update(0.6f, time), 0f)
        }
        for (time in 700L..900L step 50) {
            assertEquals(0f, detector.update(0.04f, time), 0f)
        }
        assertEquals(1f, detector.update(0.6f, 950), 0f)
    }

    @Test
    fun silenceHasNoEnergy() {
        val frame = analyzeFrame(FloatArray(AudioFrames.WINDOW))
        assertEquals(0f, frame.level, 0f)
        assertTrue(frame.spectrum.all { it == 0f })
    }

    @Test
    fun spectrumPeaksAtTheInputFrequency() {
        val bin = 7
        val samples = FloatArray(AudioFrames.WINDOW) { index ->
            (0.5 * sin(2 * PI * bin * index / AudioFrames.WINDOW)).toFloat()
        }

        val frame = analyzeFrame(samples)
        assertEquals(bin - 1, frame.spectrum.indices.maxByOrNull { frame.spectrum[it] })
        assertEquals(0.5f, frame.spectrum[bin - 1], 0.01f)
        assertEquals(0.3536f, frame.level, 0.001f)
    }
}
