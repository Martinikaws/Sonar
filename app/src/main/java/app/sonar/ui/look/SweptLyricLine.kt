package app.sonar.ui.look

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import app.sonar.data.LyricLine
import app.sonar.data.LyricWord
import java.text.BreakIterator
import java.util.Locale

internal const val LookNextLineAlpha = 0.72f
internal const val LookUnsungAlpha = 0.68f

internal data class TimedText(val word: LyricWord, val boundaries: List<Int>)

internal fun timedText(line: LyricLine): List<TimedText> {
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(line.text) }
    var cursor = 0
    return line.words.mapNotNull { word ->
        val text = word.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        val start = line.text.indexOf(text, cursor)
        if (start < 0) return@mapNotNull null
        val end = start + text.length
        if (!characters.isBoundary(start) || !characters.isBoundary(end)) return@mapNotNull null
        cursor = end
        val boundaries = buildList {
            add(start)
            var offset = characters.following(start)
            while (offset < end) {
                add(offset)
                offset = characters.next()
            }
            add(end)
        }
        TimedText(word, boundaries)
    }
}

internal fun wordSweep(word: LyricWord, positionMs: Long): Float = when {
    positionMs < word.startMs -> 0f
    positionMs >= word.endMs -> 1f
    else -> ((positionMs.toDouble() - word.startMs) / (word.endMs.toDouble() - word.startMs)).toFloat()
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun SweptLyricLine(
    line: LyricLine,
    positionMs: () -> Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    dimAlpha: Float = LookUnsungAlpha,
    reducedMotion: Boolean = rememberLookReducedMotion(),
) {
    val timed = remember(line) { timedText(line) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val sweep = timed.isNotEmpty() && !reducedMotion
    val geometry = remember(layout, timed, sweep) {
        layout?.takeIf { sweep && it.layoutInput.text.text == line.text }?.let(::SweepGeometry)
    }
    Text(
        text = line.text,
        color = if (sweep) color.copy(alpha = color.alpha * dimAlpha) else color,
        style = style,
        textAlign = TextAlign.Start,
        onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val cached = geometry ?: return@drawWithContent
            val result = cached.layout
            val now = positionMs()
            val sungEnd = timed.indexOfFirst { wordSweep(it.word, now) < 1f }
                .let { if (it == -1) timed.lastIndex else it - 1 }
            val prefixEnd = when {
                sungEnd == timed.lastIndex -> line.text.length
                sungEnd >= 0 -> timed[sungEnd].boundaries.last()
                else -> 0
            }
            if (prefixEnd > 0) {
                clipPath(cached.path(0, prefixEnd)) { drawText(result, color = color) }
            }

            for (index in sungEnd + 1..timed.lastIndex) {
                val (word, boundaries) = timed[index]
                val progress = wordSweep(word, now)
                if (progress <= 0f) continue
                val start = boundaries.first()
                if (progress >= 1f) {
                    clipPath(cached.path(start, boundaries.last())) {
                        drawText(result, color = color)
                    }
                    continue
                }
                val count = boundaries.lastIndex
                val swept = progress * count
                val glyph = swept.toInt().coerceAtMost(count - 1)
                val edge = boundaries[glyph]
                val end = boundaries[glyph + 1]
                if (edge > start) {
                    clipPath(cached.path(start, edge)) { drawText(result, color = color) }
                }
                val (box, rtl) = cached.glyph(edge, end)
                val width = box.width * (swept - glyph)
                if (width > 0f) {
                    val boundary = sweepBoundary(box.left, box.right, width, rtl)
                    val left = if (rtl) boundary else box.left
                    val right = if (rtl) box.right else boundary
                    clipRect(left, box.top, right, box.bottom) { drawText(result, color = color) }
                }
            }
        },
    )
}

internal fun sweepBoundary(left: Float, right: Float, width: Float, rtl: Boolean): Float =
    if (rtl) right - width else left + width

private class SweepGeometry(val layout: TextLayoutResult) {
    private val paths = mutableMapOf<Long, Path>()
    private val glyphs = mutableMapOf<Long, Glyph>()

    fun path(start: Int, end: Int): Path =
        paths.getOrPut(rangeKey(start, end)) { layout.getPathForRange(start, end) }

    fun glyph(start: Int, end: Int): Glyph = glyphs.getOrPut(rangeKey(start, end)) {
        Glyph(path(start, end).getBounds(), layout.getBidiRunDirection(start) == ResolvedTextDirection.Rtl)
    }

    private fun rangeKey(start: Int, end: Int): Long = (start.toLong() shl 32) or end.toLong()

    data class Glyph(val box: Rect, val rtl: Boolean)
}
