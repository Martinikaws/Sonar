package app.sonar.lyricsx

import android.content.Context
import app.sonar.data.Lyrics
import kotlin.math.abs

internal class TrackLyricsOffsets(
    private val read: (String) -> Long,
    private val write: (String, Long) -> Unit,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("track_lyrics_offsets", Context.MODE_PRIVATE),
    )

    private constructor(preferences: android.content.SharedPreferences) : this(
        { preferences.getLong(it, 0L) },
        { uri, offset ->
            val edit = preferences.edit()
            if (offset == 0L) edit.remove(uri) else edit.putLong(uri, offset)
            edit.apply()
        },
    )

    operator fun get(uri: String): Long = read(uri).coerceIn(-LIMIT_MS, LIMIT_MS)

    fun set(uri: String, offsetMs: Long): Long {
        require(uri.isNotBlank())
        val bounded = offsetMs.coerceIn(-LIMIT_MS, LIMIT_MS)
        write(uri, bounded)
        return bounded
    }

    companion object {
        const val STEP_MS = 100L
        const val LIMIT_MS = 5_000L
    }
}

/** A positive offset delays the words. Calibration replaces the track offset. */
internal fun calibrationOffset(positionMs: Long, outputDelayMs: Long, lineMs: Long): Long =
    positionMs - outputDelayMs - lineMs

internal fun lyricsPosition(positionMs: Long, outputDelayMs: Long, trackOffsetMs: Long): Long {
    val adjusted = positionMs - outputDelayMs - trackOffsetMs
    return if (trackOffsetMs == 0L) adjusted.coerceAtLeast(0L) else adjusted
}

internal fun lyricSeekPosition(lineMs: Long, outputDelayMs: Long, trackOffsetMs: Long): Long =
    (lineMs + outputDelayMs + trackOffsetMs).coerceAtLeast(0L)

internal fun calibrationLines(lyrics: Lyrics?, heardMs: Long): List<Int> {
    val lines = lyrics?.takeIf { it.synced }?.lines ?: return emptyList()
    return lines.mapIndexedNotNull { index, line ->
        line.startTimeMs?.takeIf { line.text.isNotBlank() }?.let { index to it }
    }.sortedBy { (_, time) -> abs(time - heardMs) }
        .take(6)
        .sortedBy { it.second }
        .map { it.first }
}

internal fun currentLyricLine(lyrics: Lyrics?, heardMs: Long): Int? {
    val lines = lyrics?.takeIf { it.synced }?.lines ?: return null
    val index = lines.indexOfLast { (it.startTimeMs ?: 0L) <= heardMs }
    return index.takeIf { it >= 0 && lines[it].text.isNotBlank() }
}

internal fun lyricsShareText(lyrics: Lyrics, selected: Set<Int>): String {
    val text = lyrics.lines.mapIndexedNotNull { index, line ->
        line.text.trim().takeIf { index in selected && it.isNotEmpty() }
    }.joinToString("\n")
    val credit = lyrics.credit ?: return text
    if (text.isEmpty()) return text
    val attribution = listOfNotNull(
        credit.provider, credit.uploader, credit.uploaderUrl, credit.maker, credit.makerUrl,
    ).filter { it.isNotBlank() }.distinct().joinToString("\n")
    return if (attribution.isEmpty()) text else "$text\n\n$attribution"
}
