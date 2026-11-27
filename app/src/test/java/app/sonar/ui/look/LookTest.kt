package app.sonar.ui.look

import app.sonar.data.LyricLine
import app.sonar.data.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LookTest {
    @Test
    fun persistedKeysRoundTripAndUnknownValuesFallBack() {
        assertEquals(Look.entries.size, Look.entries.map { it.key }.toSet().size)
        for (look in Look.entries) assertEquals(look, Look.fromKey(look.key))
        assertEquals(Look.STARRY_NIGHT, Look.fromKey(" STARRY_NIGHT "))
        assertEquals(Look.CLASSIC, Look.fromKey("future"))
        assertEquals(Look.CLASSIC, Look.fromKey(null))
    }

    @Test
    fun respectsWordBoundsIncludingInstantAndReversedTimings() {
        val word = LyricWord(100, 300, "one")
        assertEquals(0f, wordSweep(word, 99), 0f)
        assertEquals(0f, wordSweep(word, 100), 0f)
        assertEquals(0.5f, wordSweep(word, 200), 0f)
        assertEquals(1f, wordSweep(word, 300), 0f)
        assertEquals(0f, wordSweep(word.copy(endMs = 99), 99), 0f)
        assertEquals(1f, wordSweep(word.copy(endMs = 100), 100), 0f)
        assertEquals(1f, wordSweep(word.copy(endMs = 99), 100), 0f)
    }

    @Test
    fun locatesRepeatedWordsAndPreservesMultiUnitCharacters() {
        val sample = "go, go 😀 e\u0301"
        val words = listOf("go ", "missing", "go", "😀", "e\u0301")
            .map { LyricWord(0, 100, it) }
        assertEquals(
            listOf(listOf(0, 1, 2), listOf(4, 5, 6), listOf(7, 9), listOf(10, 12)),
            timedText(LyricLine(0L, sample, words)).map { it.boundaries },
        )
    }
}
