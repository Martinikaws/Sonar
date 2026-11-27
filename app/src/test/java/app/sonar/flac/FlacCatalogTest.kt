package app.sonar.flac

import app.sonar.audiosource.SpotifyTrackKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlacCatalogTest {
    private val key = SpotifyTrackKey("id", "A Song", listOf("An Artist"), 180_000)
    private val track = FlacTrack("a song", "An Artist", "The Album", 181_999, "one")

    @Test
    fun requiresExactEditionCreditsAndAlbumWithTightDuration() {
        assertEquals(track, matchFlac(key, "The Album", listOf(track)))
        assertNull(matchFlac(key, "", listOf(track)))
        assertNull(matchFlac(key.copy(artists = listOf("An Artist", "Guest")), "The Album", listOf(track)))
        val wrong = listOf(
            track.copy(title = "A Song (Live)"),
            track.copy(title = "A Song - 2024 Remaster"),
            track.copy(title = "A Song (Clean)"),
            track.copy(artist = "Cover Artist"),
            track.copy(album = "The Album (Deluxe)"),
            track.copy(durationMs = 182_001),
            track.copy(durationMs = 0),
        )
        assertNull(matchFlac(key, "The Album", wrong))
        assertNull(matchFlac(key, "The Album", listOf(track, track.copy(address = "another"))))
        assertEquals(track, matchFlac(key, "The Album", listOf(track, track)))
    }

    @Test
    fun acceptsHttpsServerWithBasePathAndRejectsCredentialsAndQuery() {
        assertEquals("https://music.example:8443/nav/", flacServer(" https://music.example:8443/nav/ ")?.toString())
        listOf(
            "http://music.example", "https:///music.example", "https://",
            "https://user:pass@music.example", "https://@music.example", "https://music.example?a=1",
            "https://music.example/#part", "https://music.example\\other", "https://music.example/a b",
            "https://music.example:99999", "content://music.example/track",
        ).forEach { assertNull(it, flacServer(it)) }
        assertEquals("band2024-01-01", archiveId("https://archive.org/details/band2024-01-01/"))
        assertNull(archiveId("https://archive.org.example/details/band2024-01-01"))
    }

    @Test
    fun jamendoOnlyAcceptsFlacFromItsOwnDomain() {
        val body = JSONObject("""
            {"headers":{"status":"success"},"results":[
              {"name":"A Song","artist_name":"An Artist","album_name":"The Album","duration":180,
               "audio":"https://cdn.jamendo.com/stream?format=flac"},
              {"name":"MP3","audio":"https://cdn.jamendo.com/a.mp3"},
              {"name":"Other host","audio":"https://jamendo.com.example/a.flac"},
              {"name":"Cleartext","audio":"http://cdn.jamendo.com/a.flac"}
            ]}
        """)
        val result = parseJamendo(body).single()
        assertEquals("A Song", result.title)
        assertEquals("The Album", result.album)
        assertEquals(180_000L, result.durationMs)
    }

    @Test
    fun navidromeParsesOnlyOriginalFlacCandidates() {
        val body = JSONObject("""
            {"subsonic-response":{"status":"ok","searchResult3":{"song":[
              {"id":"abc","title":"A Song","artist":"An Artist","album":"The Album","duration":180,"suffix":"flac"},
              {"id":"def","title":"MP3","suffix":"mp3"},
              {"title":"No ID","contentType":"audio/flac"}
            ]}}}
        """)
        val result = parseNavidrome(body).single()
        assertEquals("abc", result.address)
        assertEquals("An Artist", result.artist)
        assertEquals(180_000L, result.durationMs)
    }

    @Test
    fun archiveOnlyReturnsDownloadableFlacFromLiveMusicArchive() {
        val body = JSONObject("""
            {"metadata":{"mediatype":"etree","creator":["An Artist"],"title":"The Album"},"files":[
              {"name":"disc/a song.flac","title":"A Song","length":"180.5"},
              {"name":"private.flac","private":"true"},
              {"name":"../escape.flac"},
              {"name":"a.mp3"}
            ]}
        """)
        val result = parseArchive("show", body).single()
        assertEquals("https://archive.org/download/show/disc/a%20song.flac", result.address)
        assertEquals(180_500L, result.durationMs)
        body.getJSONObject("metadata").put("no-download", true)
        assertEquals(emptyList<FlacTrack>(), parseArchive("show", body))
    }
}
