package app.sonar.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioParsingTest {
    @Test fun keepsOnlyEncryptedStreamsAndCountsTheRest() {
        val text = """
            [
              {"stationuuid":"a","name":" A ","url":"http://a.example.com/raw","url_resolved":"https://a.example.com/live","tags":"pop, rock,","country":"Italy","bitrate":128,"hls":0,"lastcheckok":1,"votes":5},
              {"stationuuid":"b","name":"B","url":"https://b.example.com/raw","url_resolved":"http://b.example.com/live"},
              {"stationuuid":"c","name":"C","url":"https://c.example.com/live","url_resolved":""},
              {"stationuuid":"d","name":"D","url_resolved":"https://d.example.com/index.m3u8?x=1"},
              {"stationuuid":"e","name":"E","url_resolved":"https://e.example.com/s","hls":1},
              {"stationuuid":"","name":"no id","url_resolved":"https://f.example.com/s"}
            ]
        """
        val r = RadioParsing.stations(text)
        assertEquals(listOf("a", "c", "d", "e"), r.stations.map { it.uuid })
        assertEquals(1, r.unplayable)
        val a = r.stations[0]
        assertEquals("A", a.name)
        assertEquals("https://a.example.com/live", a.url)
        assertEquals(listOf("pop", "rock"), a.tags)
        assertEquals(128, a.bitrate)
        assertFalse(a.hls)
        assertTrue(r.stations[2].hls)
        assertTrue(r.stations[3].hls)
    }

    @Test fun limitKeepsTheFirstPlayableOnes() {
        val text = (1..5).joinToString(",", "[", "]") { """{"stationuuid":"$it","name":"S$it","url":"https://x.example.com/$it"}""" }
        assertEquals(listOf("1", "2"), RadioParsing.stations(text, limit = 2).stations.map { it.uuid })
    }

    @Test fun duplicatePlayableStationIdsDoNotConsumeTheLimit() {
        val text = listOf("a", "a", "b").joinToString(",", "[", "]") {
            """{"stationuuid":"$it","url":"https://x.example.com/$it"}"""
        }
        assertEquals(listOf("a", "b"), RadioParsing.stations(text, limit = 2).stations.map { it.uuid })
    }

    @Test fun nullsAndBlankNamesDoNotBreakAStation() {
        val r = RadioParsing.stations("""[{"stationuuid":"a","name":null,"url":"https://a.example.com/s","bitrate":null}]""")
        assertEquals("?", r.stations.single().name)
        assertEquals(0, r.stations.single().bitrate)
    }

    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun aPageThatIsNotJsonFails() {
        RadioParsing.stations("<html>captive portal</html>")
    }

    @Test fun tagsDropBlankNames() {
        assertEquals(listOf("pop", "jazz"), RadioParsing.tags("""[{"name":"pop","stationcount":9},{"name":" "},{"name":"jazz"}]"""))
    }

    @Test fun playableMeansHttpsWithAHost() {
        assertTrue(RadioParsing.playable("https://icecast.example.com/live.mp3"))
        assertTrue(RadioParsing.playable("HTTPS://x.example.com:8443/s"))
        assertFalse(RadioParsing.playable("http://x.example.com/s"))
        assertFalse(RadioParsing.playable("rtsp://x.example.com/s"))
        assertFalse(RadioParsing.playable("https:///nohost"))
        assertFalse(RadioParsing.playable("https://user:password@x.example.com/live"))
        assertFalse(RadioParsing.playable(""))
    }

    @Test fun favoritesAndRecentsRoundTrip() {
        fun s(id: String, url: String = "https://x.example.com/$id") = RadioStation(id, id, url, tags = listOf("t"), hls = true)
        var d = RadioData().toggled(s("a")).toggled(s("b")).toggled(s("insecure", "http://x.example.com/i"))
        assertEquals(listOf("b", "a"), d.favorites.map { it.uuid })
        d = d.toggled(s("a"))
        assertEquals(listOf("b"), d.favorites.map { it.uuid })
        for (i in 1..12) d = d.played(s("r$i"))
        d = d.played(s("r5"))
        assertEquals(RadioData.MAX_RECENTS, d.recents.size)
        assertEquals("r5", d.recents.first().uuid)
        assertEquals(1, d.recents.count { it.uuid == "r5" })
        assertEquals(d, RadioData.decode(RadioData.encode(d)))
        assertEquals(RadioData(), RadioData.decode("garbage"))
        assertEquals(RadioData(), RadioData.decode(null))
    }
}
