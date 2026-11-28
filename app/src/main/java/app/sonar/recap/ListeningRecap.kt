package app.sonar.recap

import app.sonar.data.CatalogTrack
import app.sonar.data.ListeningEvent
import app.sonar.data.LocalLibrary
import java.time.YearMonth
import java.time.ZoneId

internal enum class RecapPeriod { MONTH, YEAR, ALL }

internal enum class RecapSource { SPOTIFY, YOUTUBE_MUSIC, DOWNLOAD, LOCAL, OTHER }

internal data class SourceTotal(val plays: Int = 0, val milliseconds: Long = 0)

internal data class ListeningRecap(
    val plays: Int,
    val minutes: Long,
    val tracks: List<Pair<CatalogTrack, Int>>,
    val artists: List<Pair<String, Int>>,
    val albums: List<Pair<CatalogTrack, Int>>,
    val sources: Map<RecapSource, SourceTotal>,
)

internal fun listeningRecap(
    events: List<ListeningEvent>,
    period: RecapPeriod,
    month: YearMonth,
    zone: ZoneId = ZoneId.systemDefault(),
): ListeningRecap {
    val first = if (period == RecapPeriod.YEAR) month.withMonth(1) else month
    val end = first.plusMonths(if (period == RecapPeriod.YEAR) 12 else 1)
    val from = first.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val toExclusive = end.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val months = generateSequence(first) { it.plusMonths(1) }
        .takeWhile { it < end }.map { it.toString() }.toSet()
    val counted = mutableListOf<CatalogTrack>()
    val sources = mutableMapOf<RecapSource, SourceTotal>()
    for (event in events) {
        val inRange = period == RecapPeriod.ALL || (event.spotifyAt ?: event.startedAt) in from until toExclusive
        val play = event.qualified && inRange
        // Month buckets preserve measured time across a boundary. Older events only have a total.
        val ms = when {
            period == RecapPeriod.ALL || (event.measuredMonths.isEmpty() && event.startedAt in from until toExclusive) -> event.listenedMs
            else -> event.measuredMonths.entries.sumOf { (key, value) -> if (key in months) value else 0L }
        }
        if (play) counted += event.track
        if (play || ms > 0) {
            val uri = event.track.uri
            val source = when {
                event.downloaded -> RecapSource.DOWNLOAD
                uri.startsWith(LocalLibrary.PREFIX) || uri.startsWith(LocalLibrary.SPOTIFY_PREFIX) -> RecapSource.LOCAL
                uri.startsWith("spotify:") -> RecapSource.SPOTIFY
                uri.startsWith("ytmusic:") -> RecapSource.YOUTUBE_MUSIC
                else -> RecapSource.OTHER
            }
            val previous = sources[source] ?: SourceTotal()
            sources[source] = SourceTotal(previous.plays + if (play) 1 else 0, previous.milliseconds + ms)
        }
    }

    val tracks = counted.groupBy { it.uri }.values.map { it.last() to it.size }
        .sortedWith(compareByDescending<Pair<CatalogTrack, Int>> { it.second }.thenBy { it.first.name })
    val artists = counted.flatMap { track ->
        track.artists.map { it.name }.ifEmpty { listOf(track.artist) }.filter { it.isNotBlank() }.distinct()
    }.groupingBy { it }.eachCount().toList()
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    val albums = counted.filter { it.album.isNotBlank() || !it.albumUri.isNullOrBlank() }
        .groupBy { it.albumUri?.takeIf(String::isNotBlank) ?: (it.album to (it.artistUri ?: it.artist)) }
        .values.map { group -> group.last() to group.size }
        .sortedWith(compareByDescending<Pair<CatalogTrack, Int>> { it.second }.thenBy { it.first.album })

    return ListeningRecap(counted.size, sources.values.sumOf { it.milliseconds } / 60_000, tracks, artists, albums, sources)
}

