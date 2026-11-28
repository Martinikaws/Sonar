package app.sonar.recap

import app.sonar.data.CatalogArtist
import app.sonar.data.CatalogTrack
import app.sonar.data.ListeningEvent
import app.sonar.data.ListeningSampler
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class ListeningRecapTest {
    private val january = YearMonth.of(2026, 1)
    private val spotify = CatalogTrack(
        uri = "spotify:track:one", name = "One", artist = "A",
        artists = listOf(CatalogArtist("A", "a"), CatalogArtist("A", "a"), CatalogArtist("B", "b")),
        album = "Shared title", albumUri = "spotify:album:one",
    )
    private val youtube = CatalogTrack(
        uri = "ytmusic:track:two", name = "Two", artist = "C", album = "Shared title",
    )
    private val local = CatalogTrack("local:track:three", "Three", "D")

    @Test fun binsMeasuredTimeAcrossYearsAndRanksOnlyQualifiedPlays() {
        val before = Instant.parse("2025-12-31T23:59:59Z").toEpochMilli()
        val after = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val events = listOf(
            ListeningEvent(
                "crossing", spotify, before, listenedMs = 120_000, qualified = true,
                measuredMonths = mapOf("2025-12" to 60_000, "2026-01" to 60_000),
            ),
            ListeningEvent("remote", spotify, before, qualified = true, spotifyAt = after),
            ListeningEvent("youtube", youtube, after, listenedMs = 60_000, qualified = true),
            ListeningEvent("local", local, after, listenedMs = 30_000),
            ListeningEvent("later", spotify, Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(), qualified = true),
        )

        val month = listeningRecap(events, RecapPeriod.MONTH, january, ZoneOffset.UTC)
        assertEquals(2, month.plays)
        assertEquals(2L, month.minutes)
        assertEquals(listOf(spotify to 1, youtube to 1), month.tracks)
        assertEquals(listOf("A" to 1, "B" to 1, "C" to 1), month.artists)
        assertEquals(mapOf(spotify to 1, youtube to 1), month.albums.toMap())
        assertEquals(SourceTotal(1, 60_000), month.sources[RecapSource.SPOTIFY])
        assertEquals(SourceTotal(1, 60_000), month.sources[RecapSource.YOUTUBE_MUSIC])
        assertEquals(SourceTotal(0, 30_000), month.sources[RecapSource.LOCAL])

        val year = listeningRecap(events, RecapPeriod.YEAR, january, ZoneOffset.UTC)
        assertEquals(3, year.plays)
        assertEquals(2L, year.minutes)
        assertEquals(spotify to 2, year.albums.first())

        val all = listeningRecap(events, RecapPeriod.ALL, january, ZoneOffset.UTC)
        assertEquals(4, all.plays)
        assertEquals(3L, all.minutes)
        assertEquals(SourceTotal(3, 120_000), all.sources[RecapSource.SPOTIFY])
    }

    @Test fun separatesDownloadAndStreamOfTheSameTrack() {
        val sampler = ListeningSampler()
        val track = spotify.copy(durationMs = 4_000)
        sampler.sample(track, true, 1_000, 1_000)
        sampler.sample(track, true, 3_000, 3_000)
        val stream = requireNotNull(sampler.sample(track, true, 4_000, 4_000, downloaded = true))
        val download = requireNotNull(sampler.sample(track, true, 6_000, 6_000, downloaded = true))

        val recap = listeningRecap(listOf(stream, download), RecapPeriod.ALL, january, ZoneOffset.UTC)
        assertEquals(listOf(track to 2), recap.tracks)
        assertEquals(SourceTotal(1, 2_000), recap.sources[RecapSource.SPOTIFY])
        assertEquals(SourceTotal(1, 2_000), recap.sources[RecapSource.DOWNLOAD])
    }

    @Test fun usesLocalCalendarBoundariesAndHandlesEmptyHistory() {
        val event = ListeningEvent(
            "boundary", spotify, Instant.parse("2025-12-31T23:30:00Z").toEpochMilli(), qualified = true,
        )
        assertEquals(0, listeningRecap(listOf(event), RecapPeriod.MONTH, january, ZoneOffset.UTC).plays)
        assertEquals(1, listeningRecap(listOf(event), RecapPeriod.MONTH, january, ZoneOffset.ofHours(1)).plays)
        assertEquals(emptyMap<RecapSource, SourceTotal>(), listeningRecap(emptyList(), RecapPeriod.ALL, january).sources)
    }
}
