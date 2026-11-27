package app.sonar.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.sonar.R
import app.sonar.ui.components.Artwork
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val RADIO_ROUTE = "live-radio"

/**
 * Live radio: search or browse the radio-browser.info directory, play a station on the phone's
 * own player, keep favourites and the last few stations. [player] is the session's controller,
 * null until it connects, when tapping a station does nothing yet.
 */
@Composable
fun RadioScreen(player: Player?, contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { RadioStore.init(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val saved = RadioStore.data

    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var results by remember { mutableStateOf<RadioResults?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) { tags = runCatching { RadioDirectory.tags() }.getOrDefault(emptyList()) }
    LaunchedEffect(query, tag, attempt) {
        loading = true
        failed = false
        if (query.isNotBlank()) delay(400) // a newer keystroke cancels this one
        try {
            results = RadioDirectory.search(query, tag)
        } catch (e: Exception) {
            if (!isDirectoryFailure(e)) throw e
            results = null
            failed = true
        }
        loading = false
    }

    var current by remember(player) { mutableStateOf(player?.currentMediaItem?.mediaId) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current = mediaItem?.mediaId
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    val play: (RadioStation) -> Unit = { station ->
        if (player != null) {
            RadioPlayback.play(player, station)
            RadioStore.played(station)
            scope.launch { RadioDirectory.reportClick(station.uuid) }
        }
    }
    val browsing = query.isBlank() && tag.isBlank()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = contentPadding.calculateTopPadding(), start = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.back))
                    }
                    Text(
                        stringResource(R.string.radio_title),
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (it.isNotBlank()) tag = "" },
                    placeholder = { Text(stringResource(R.string.radio_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (tags.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { t ->
                        FilterChip(
                            selected = t == tag,
                            onClick = { tag = if (t == tag) "" else t; query = "" },
                            label = { Text(t) },
                        )
                    }
                }
            }
            if (browsing && saved.favorites.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.radio_favorites)) }
                items(saved.favorites, key = { "fav-${it.uuid}" }) { StationRow(it, current, play) }
            }
            if (browsing && saved.recents.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.radio_recent)) }
                items(saved.recents, key = { "recent-${it.uuid}" }) { StationRow(it, current, play) }
            }
            item {
                SectionTitle(
                    when {
                        query.isNotBlank() -> stringResource(R.string.radio_results)
                        tag.isNotBlank() -> tag.replaceFirstChar { it.uppercase() }
                        else -> stringResource(R.string.radio_popular)
                    },
                )
            }
            when {
                failed -> item {
                    Message(stringResource(R.string.radio_error)) {
                        TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                loading -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                results?.stations.isNullOrEmpty() -> item { Message(stringResource(R.string.radio_no_results)) }
                else -> items(results!!.stations, key = { "hit-${it.uuid}" }) { StationRow(it, current, play) }
            }
            val hidden = results?.unplayable ?: 0
            if (!loading && !failed && hidden > 0) item { Message(stringResource(R.string.radio_unplayable, hidden)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
private fun StationRow(station: RadioStation, current: String?, onPlay: (RadioStation) -> Unit) {
    val playing = current == RadioPlayback.mediaId(station)
    val favorite = RadioStore.isFavorite(station.uuid)
    val subtitle = listOfNotNull(
        station.country.takeIf { it.isNotBlank() },
        station.tags.firstOrNull(),
        station.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.radio_play, station.name)) { onPlay(station) }
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = station.favicon.takeIf { RadioParsing.playable(it) },
            title = station.name,
            modifier = Modifier.size(48.dp),
            corner = 8.dp,
            decodeSize = 48.dp,
        )
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                station.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { RadioStore.toggle(station) }, modifier = Modifier.size(48.dp)) {
            Icon(
                Lucide.Heart,
                contentDescription = stringResource(if (favorite) R.string.radio_unfavorite else R.string.radio_favorite),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
