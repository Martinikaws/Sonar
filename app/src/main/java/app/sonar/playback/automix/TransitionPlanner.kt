package app.sonar.playback.automix

import kotlin.math.abs
import kotlin.math.ceil

internal data class TransitionTiming(
    val outMs: Long,
    val inMs: Long,
    val fadeMs: Long,
    val speedIn: Float = 1f,
) {
    fun fits(outLength: Long, inLength: Long): Boolean =
        outMs in 1L until outLength && inMs in 0L until inLength &&
            fadeMs in 1L..(outLength - outMs) && speedIn.isFinite() &&
            speedIn in 0.9f..1.1f && ceil(fadeMs * speedIn.toDouble()) <= inLength - inMs
}

internal object TransitionPlanner {
    // Astral aligns the outgoing and incoming cues before changing the incoming tempo.
    fun plan(
        outLength: Long,
        inLength: Long,
        maxFade: Long,
        outCue: Long,
        inCue: Long,
        outBars: LongArray,
        inBars: LongArray,
        beatMatch: Boolean,
    ): TransitionTiming? {
        if (outLength <= 1 || inLength <= 0) return null
        val limit = minOf(maxFade, outLength - 1, inLength)
        if (limit <= 0) return null
        val exit = outCue.takeIf { it in 1L..outLength } ?: outLength
        val entry = inCue.coerceIn(0, inLength - 1)
        var best: TransitionTiming? = null
        var bestCost = Double.POSITIVE_INFINITY
        if (beatMatch) {
            for (end in maxOf(2, outBars.size - 16) until outBars.size) {
                val start = outBars[end - 2]
                val fade = outBars[end] - start
                if (fade !in 1L..limit || abs(outBars[end] - exit) > limit) continue
                for (index in 0 until minOf(16, inBars.size - 2)) {
                    val cue = inBars[index]
                    if (abs(cue - entry) > limit) continue
                    val speed = (inBars[index + 2] - cue) / fade.toDouble()
                    val timing = TransitionTiming(start, cue, fade, speed.toFloat())
                    if (!timing.fits(outLength, inLength)) continue
                    val cost = abs(outBars[end] - exit) + abs(cue - entry) + abs(speed - 1) * limit
                    if (cost < bestCost) {
                        best = timing
                        bestCost = cost
                    }
                }
            }
        }
        val outgoing = (exit - limit / 2).coerceIn(1, outLength - limit)
        val incoming = (entry - limit / 2).coerceIn(0, inLength - limit)
        return best ?: TransitionTiming(outgoing, incoming, limit)
    }

    fun snap(cue: Long, bars: LongArray, bounds: LongRange): Long =
        bars.filter { it in bounds }.minByOrNull { abs(it - cue) } ?: cue
}
