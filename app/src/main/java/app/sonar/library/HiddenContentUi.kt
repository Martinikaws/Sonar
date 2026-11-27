package app.sonar.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.data.CatalogTrack
import app.sonar.ui.theme.InkDim

@Composable
private fun SectionLabel(text: String) = Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelLarge,
    color = InkDim,
    modifier = Modifier.padding(start = 26.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
)

@Composable
private fun EmptyNote(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = InkDim,
    modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp),
)

/** A line of text with up to two text buttons on its right; every target is at least 44dp. */
@Composable
private fun ActionLine(
    title: String,
    subtitle: String?,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(start = 26.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (secondary != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 44.dp)) { Text(secondary) }
        }
        TextButton(onClick = onPrimary, modifier = Modifier.heightIn(min = 44.dp)) { Text(primary) }
    }
}

/** Settings page that lists what was hidden or banned, with a button to bring each back. */
@Composable
fun HiddenContentPage(
    store: HiddenContentStore,
    contentPadding: PaddingValues,
    header: @Composable () -> Unit,
) {
    val hidden by store.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("top") { header() }
        item("songs-label") { SectionLabel(stringResource(R.string.hidden_songs)) }
        if (hidden.songs.isEmpty()) item("songs-empty") { EmptyNote(stringResource(R.string.hidden_songs_empty)) }
        items(hidden.songs, key = { "s" + it.uri }) { song ->
            ActionLine(song.name, song.artist, stringResource(R.string.hidden_unhide), onPrimary = { store.showSong(song.uri) })
        }
        item("artists-label") { SectionLabel(stringResource(R.string.hidden_artists)) }
        if (hidden.artists.isEmpty()) item("artists-empty") { EmptyNote(stringResource(R.string.hidden_artists_empty)) }
        items(hidden.artists, key = { "a" + it.uri }) { artist ->
            ActionLine(artist.name, null, stringResource(R.string.hidden_unban), onPrimary = { store.unbanArtist(artist.uri) })
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.hidden_playlist_name)) },
                singleLine = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()); onDismiss() },
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.fix2_library_save)) }
        },
    )
}

/** Settings page for the on-device playlists: create, rename, delete, remove tracks, play. */
@Composable
fun LocalPlaylistsPage(
    store: LocalPlaylistStore,
    onPlay: (List<CatalogTrack>, Int) -> Unit,
    contentPadding: PaddingValues,
    header: @Composable () -> Unit,
) {
    val all by store.state.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    val open = all.lists.firstOrNull { it.id == openId }
    if (openId != null && open == null) openId = null

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("top") { header() }
        if (open == null) {
            item("new") {
                ActionLine(stringResource(R.string.hidden_local_new), null, stringResource(R.string.hidden_create), onPrimary = { creating = true })
            }
            if (all.lists.isEmpty()) item("empty") { EmptyNote(stringResource(R.string.hidden_local_empty)) }
            items(all.lists, key = { it.id }) { list ->
                ActionLine(
                    list.name,
                    stringResource(R.string.hidden_local_count, list.tracks.size),
                    stringResource(R.string.hidden_open),
                    onPrimary = { openId = list.id },
                    onClick = { openId = list.id },
                )
            }
        } else {
            item("bar") {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openId = null }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(stringResource(R.string.back))
                    }
                    Text(open.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item("actions") {
                Row(Modifier.padding(horizontal = 14.dp)) {
                    TextButton(
                        onClick = { onPlay(open.tracks, 0) },
                        enabled = open.tracks.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.play)) }
                    TextButton(onClick = { renaming = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(stringResource(R.string.hidden_rename))
                    }
                    TextButton(onClick = { store.delete(open.id) }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(stringResource(R.string.hidden_delete))
                    }
                }
            }
            if (open.tracks.isEmpty()) item("tracks-empty") { EmptyNote(stringResource(R.string.hidden_local_tracks_empty)) }
            items(open.tracks, key = { it.uri }) { track ->
                ActionLine(
                    track.name, track.artist, stringResource(R.string.hidden_remove),
                    onPrimary = { store.remove(open.id, track.uri) },
                    onClick = { onPlay(open.tracks, open.tracks.indexOf(track)) },
                )
            }
        }
    }
    if (creating) NameDialog(stringResource(R.string.hidden_local_new), "", { store.create(it) }, { creating = false })
    if (renaming && open != null) {
        NameDialog(stringResource(R.string.hidden_rename), open.name, { store.rename(open.id, it) }, { renaming = false })
    }
}

/** Picks one of the on-device playlists for [track], or makes a new one. */
@Composable
fun AddToLocalPlaylistDialog(store: LocalPlaylistStore, track: CatalogTrack, onDismiss: () -> Unit) {
    val all by store.state.collectAsState()
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        NameDialog(stringResource(R.string.hidden_local_new), "", { name ->
            store.create(name)
            // The new list is the last one; the state flow has already moved by the time this returns.
            store.state.value.lists.lastOrNull()?.let { store.add(it.id, track) }
        }, onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hidden_add_local)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item("new") { LibraryChoiceRow(stringResource(R.string.hidden_local_new)) { creating = true } }
                items(all.lists, key = { it.id }) { list ->
                    LibraryChoiceRow(list.name) { store.add(list.id, track); onDismiss() }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.fix2_library_done)) }
        },
    )
}
