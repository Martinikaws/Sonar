package app.sonar.lyricsx

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.backend.lyrics.LyricsQuery
import app.sonar.backend.lyrics.LyricsSource
import app.sonar.data.Lyrics

// Entries follow the lyrics_tools_labels resource supplied by the player hook.
private enum class Label {
    TOOLS, TIMING, EARLIER, LATER, RESET, RESYNC, RESYNC_HINT, SHARE_CURRENT,
    SELECT_LINES, SHARE, DIAGNOSTICS, CURRENT_PROVIDER, NO_ATTEMPTS,
    ANSWERED, NO_LYRICS, FAILED, TIMED_OUT, CLOSE, MILLISECONDS, UNKNOWN,
}

private enum class Page { TOOLS, RESYNC, SELECT, DIAGNOSTICS }

@Composable
internal fun LyricsToolsButton(
    query: LyricsQuery,
    lyrics: Lyrics?,
    positionMs: State<Long>,
    heardMs: State<Long>,
    outputDelayMs: Long,
    trackOffsetMs: Long,
    onOffset: (Long) -> Unit,
    labels: Array<String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lookup = query.lookupKey()
    var page by remember(lookup, lyrics) { mutableStateOf<Page?>(null) }
    var selected by remember(lookup, lyrics) { mutableStateOf(emptySet<Int>()) }
    var nearby by remember(lookup, lyrics) { mutableStateOf(emptyList<Int>()) }
    val current by remember(lyrics, heardMs) { derivedStateOf { currentLyricLine(lyrics, heardMs.value) } }
    val selectable = remember(lyrics) {
        lyrics?.lines.orEmpty().mapIndexedNotNull { index, line -> index.takeIf { line.text.isNotBlank() } }
    }

    fun label(key: Label) = labels[key.ordinal]

    fun share(indices: Set<Int>) {
        val text = lyrics?.let { lyricsShareText(it, indices) }.orEmpty()
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, label(Label.SHARE)))
        page = null
    }

    TextButton(onClick = { page = Page.TOOLS }, modifier = modifier.heightIn(min = 44.dp)) {
        Text(label(Label.TOOLS))
    }

    val open = page ?: return
    AlertDialog(
        onDismissRequest = { page = null },
        title = {
            Text(label(when (open) {
                Page.TOOLS -> Label.TOOLS
                Page.RESYNC -> Label.RESYNC
                Page.SELECT -> Label.SELECT_LINES
                Page.DIAGNOSTICS -> Label.DIAGNOSTICS
            }))
        },
        text = {
            when (open) {
                Page.TOOLS -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (lyrics?.synced == true) {
                        item {
                            Text(stringResource(R.string.fix2_lyrics_timing, label(Label.TIMING), trackOffsetMs, label(Label.MILLISECONDS)))
                        }
                        item {
                            LyricsToolAction(label(Label.EARLIER), trackOffsetMs > -TrackLyricsOffsets.LIMIT_MS) {
                                onOffset(trackOffsetMs - TrackLyricsOffsets.STEP_MS)
                            }
                        }
                        item {
                            LyricsToolAction(label(Label.LATER), trackOffsetMs < TrackLyricsOffsets.LIMIT_MS) {
                                onOffset(trackOffsetMs + TrackLyricsOffsets.STEP_MS)
                            }
                        }
                        item {
                            LyricsToolAction(label(Label.RESET), trackOffsetMs != 0L) { onOffset(0L) }
                        }
                        item {
                            LyricsToolAction(label(Label.RESYNC), lyrics?.lines.orEmpty().any {
                                it.startTimeMs != null && it.text.isNotBlank()
                            }) {
                                nearby = calibrationLines(lyrics, heardMs.value)
                                page = Page.RESYNC
                            }
                        }
                    }
                    item {
                        LyricsToolAction(label(Label.SHARE_CURRENT), current != null) {
                            currentLyricLine(lyrics, heardMs.value)?.let { share(setOf(it)) }
                        }
                    }
                    item {
                        LyricsToolAction(label(Label.SELECT_LINES), selectable.isNotEmpty()) {
                            selected = emptySet()
                            page = Page.SELECT
                        }
                    }
                    item { LyricsToolAction(label(Label.DIAGNOSTICS)) { page = Page.DIAGNOSTICS } }
                }
                Page.RESYNC -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { Text(label(Label.RESYNC_HINT)) }
                    items(nearby.size) { item ->
                        val line = requireNotNull(lyrics).lines[nearby[item]]
                        LyricsToolAction(line.text) {
                            onOffset(calibrationOffset(positionMs.value, outputDelayMs, requireNotNull(line.startTimeMs)))
                            page = null
                        }
                    }
                }
                Page.SELECT -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(selectable.size) { item ->
                        val index = selectable[item]
                        val checked = index in selected
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .toggleable(checked, role = Role.Checkbox) {
                                    selected = if (it) selected + index else selected - index
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(requireNotNull(lyrics).lines[index].text, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Page.DIAGNOSTICS -> {
                    val history by LyricsDiagnostics.session.attempts.collectAsState()
                    val attempts = history[lookup].orEmpty()
                    val shown = lyrics?.source?.let { name ->
                        LyricsSource.entries.firstOrNull { it.name == name }?.label ?: name
                    } ?: label(Label.UNKNOWN)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item { Text(stringResource(R.string.fix2_lyrics_provider, label(Label.CURRENT_PROVIDER), shown)) }
                        if (attempts.isEmpty()) item { Text(label(Label.NO_ATTEMPTS)) }
                        for (source in LyricsSource.entries) {
                            val attempt = attempts[source] ?: continue
                            item {
                                val status = label(when (attempt.outcome) {
                                    LyricsLookupOutcome.ANSWERED -> Label.ANSWERED
                                    LyricsLookupOutcome.NO_LYRICS -> Label.NO_LYRICS
                                    LyricsLookupOutcome.FAILED -> Label.FAILED
                                    LyricsLookupOutcome.TIMED_OUT -> Label.TIMED_OUT
                                })
                                Text(stringResource(
                                    R.string.fix2_lyrics_attempt, source.label, status,
                                    attempt.elapsedMs, label(Label.MILLISECONDS),
                                ))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (open == Page.SELECT) {
                LyricsToolAction(label(Label.SHARE), selected.isNotEmpty()) { share(selected) }
            }
        },
        dismissButton = {
            LyricsToolAction(label(Label.CLOSE)) { page = null }
        },
    )
}

@Composable
private fun LyricsToolAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp)) {
        Text(text)
    }
}
