package app.sonar.recap

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import app.sonar.R
import app.sonar.data.CatalogTrack
import app.sonar.data.ListeningEvent
import app.sonar.ui.components.FilterChip
import app.sonar.ui.components.Artwork
import app.sonar.ui.glass.LiquidButton
import app.sonar.ui.glass.backdrop.Backdrop
import app.sonar.ui.glass.pressable
import app.sonar.ui.glass.shapes.ContinuousRoundedRectangle
import app.sonar.ui.theme.Ink
import app.sonar.ui.theme.InkDim
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ListeningRecapPage(
    events: List<ListeningEvent>,
    backdrop: Backdrop,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val current = YearMonth.now()
    var period by rememberSaveable { mutableStateOf(RecapPeriod.MONTH) }
    var monthKey by rememberSaveable { mutableStateOf(current.toString()) }
    val month = YearMonth.parse(monthKey)
    val first = remember(events) {
        events.minOfOrNull { minOf(it.startedAt, it.spotifyAt ?: it.startedAt) }?.let {
            YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        }?.coerceAtMost(current) ?: current
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidButton(onClick = onBack, backdrop = backdrop) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.home_recap_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (choice in RecapPeriod.entries) {
                val label = when (choice) {
                    RecapPeriod.MONTH -> R.string.home_recap_month
                    RecapPeriod.YEAR -> R.string.home_recap_year
                    RecapPeriod.ALL -> R.string.home_recap_all
                }
                FilterChip(
                    label = stringResource(label),
                    selected = period == choice,
                    onClick = { period = choice },
                    backdrop = backdrop,
                    modifier = Modifier.minimumInteractiveComponentSize().semantics { selected = period == choice },
                )
            }
        }
        if (period != RecapPeriod.ALL) {
            val yearly = period == RecapPeriod.YEAR
            val step = if (yearly) 12L else 1L
            Header(
                month = month,
                yearly = yearly,
                canGoBack = if (yearly) month.year > first.year else month > first,
                canGoForward = if (yearly) month.year < current.year else month < current,
                onBack = { monthKey = month.minusMonths(step).coerceAtLeast(first).toString() },
                onForward = { monthKey = month.plusMonths(step).coerceAtMost(current).toString() },
            )
        }
        AnimatedContent(
            targetState = period to month,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "recapPeriod",
        ) { (shownPeriod, shownMonth) ->
            val summary = remember(events, shownPeriod, shownMonth) { listeningRecap(events, shownPeriod, shownMonth) }
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Body(summary, shownPeriod)
                Text(
                    stringResource(R.string.home_recap_history_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                )
            }
        }
    }
}

@Composable
private fun Header(
    month: YearMonth,
    yearly: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Step(Lucide.ChevronLeft, stringResource(if (yearly) R.string.home_recap_previous_year else R.string.recap_previous), canGoBack, onBack)
            Text(
                month.format(DateTimeFormatter.ofPattern(if (yearly) "yyyy" else "LLLL yyyy", Locale.getDefault()))
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Step(Lucide.ChevronRight, stringResource(if (yearly) R.string.home_recap_next_year else R.string.recap_next), canGoForward, onForward)
        }
    }
}

@Composable
private fun Step(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(ContinuousRoundedRectangle(24.dp))
            .background(if (enabled) FILM else Color.Transparent)
            .pressable(onClick, pressedScale = 0.9f, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Ink else InkDim.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun Body(summary: ListeningRecap, period: RecapPeriod) {
    val top = summary.tracks.firstOrNull()?.first
    if (top != null) TopTrack(top, period)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(summary.plays.toString(), stringResource(R.string.recap_stat_plays), Modifier.weight(1f))
        Stat(summary.minutes.toString(), stringResource(R.string.recap_stat_minutes), Modifier.weight(1f))
    }

    if (summary.sources.isEmpty()) {
        Text(
            stringResource(R.string.home_recap_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = InkDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }

    if (summary.tracks.isNotEmpty()) {
        Section(stringResource(R.string.recap_tracks))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            summary.tracks.take(RANKED).forEachIndexed { at, (track, count) -> TrackRank(at + 1, track, count) }
        }
    }

    if (summary.artists.isNotEmpty()) {
        Section(stringResource(R.string.recap_artists))
        val most = summary.artists.first().second.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            summary.artists.take(RANKED).forEachIndexed { at, (artist, count) ->
                ArtistRank(at + 1, artist, count, count.toFloat() / most)
            }
        }
    }

    if (summary.albums.isNotEmpty()) {
        Section(stringResource(R.string.home_recap_albums))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            summary.albums.take(RANKED).forEachIndexed { at, (track, count) ->
                TrackRank(at + 1, track, count, title = track.album.ifBlank { track.name })
            }
        }
    }

    if (summary.sources.isNotEmpty()) {
        Section(stringResource(R.string.home_recap_sources))
        for (source in RecapSource.entries) {
            val total = summary.sources[source] ?: continue
            val label = when (source) {
                RecapSource.SPOTIFY -> R.string.backend_spotify
                RecapSource.YOUTUBE_MUSIC -> R.string.backend_youtube
                RecapSource.DOWNLOAD -> R.string.recap_downloads
                RecapSource.LOCAL -> R.string.local_files
                RecapSource.OTHER -> R.string.home_recap_other
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(label), color = Ink, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.home_recap_source_total, total.plays, total.milliseconds / 60_000),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                )
            }
        }
    }
}

@Composable
private fun TopTrack(track: CatalogTrack, period: RecapPeriod) {
    val shape = ContinuousRoundedRectangle(26.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .clip(shape),
    ) {
        Artwork(track.artworkUrl, track.name, Modifier.fillMaxSize(), corner = 0.dp)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp),
        ) {
            Text(
                stringResource(if (period == RecapPeriod.MONTH) R.string.recap_song_of_month else R.string.home_recap_top_track),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(
                track.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(ContinuousRoundedRectangle(22.dp))
            .background(FILM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Ink,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = InkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Rank(place: Int) {
    Text(
        place.toString(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (place == 1) Ink else InkDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(26.dp),
    )
}

@Composable
private fun TrackRank(place: Int, track: CatalogTrack, plays: Int, title: String = track.name) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(place)
        Artwork(track.artworkUrl, title, Modifier.size(46.dp), corner = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(plays.toString(), style = MaterialTheme.typography.bodyMedium, color = InkDim)
    }
}

/** An artist, with a bar as long as their share of the month's favourite. */
@Composable
private fun ArtistRank(place: Int, artist: String, plays: Int, share: Float) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(place)
        Box(
            Modifier
                .weight(1f)
                .clip(ContinuousRoundedRectangle(14.dp))
                .background(FILM),
        ) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxWidth(share.coerceIn(0.08f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(plays.toString(), style = MaterialTheme.typography.bodyMedium, color = InkDim)
            }
        }
    }
}

private const val RANKED = 5

/** The ground of the tiles: a shade of the ink, so it reads in both themes. */
private val FILM: Color @Composable get() = Ink.copy(alpha = 0.08f)
