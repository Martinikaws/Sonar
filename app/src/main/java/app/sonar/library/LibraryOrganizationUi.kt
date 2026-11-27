package app.sonar.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.data.CatalogPlaylist
import app.sonar.data.CatalogTrack
import app.sonar.ui.components.GlassMenuDivider
import app.sonar.ui.components.GlassMenuItem
import app.sonar.ui.components.TrackSheetAction
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.X
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistTrackSortItems(
    selected: PlaylistTrackSort,
    descending: Boolean,
    label: @Composable (PlaylistTrackSort) -> String,
    onSort: (PlaylistTrackSort) -> Unit,
    onToggleDescending: () -> Unit,
) {
    PlaylistTrackSort.entries.forEach { option ->
        GlassMenuItem(label(option), selected = option == selected, onClick = { onSort(option) })
    }
    GlassMenuDivider()
    GlassMenuItem(stringResource(R.string.sort_descending), checked = descending, onClick = onToggleDescending)
}

/** Pass the complete movable set at the current level, in ascending custom order. */
@Composable
fun PlaylistReorderDialog(
    playlists: List<CatalogPlaylist>,
    onMove: (fromUri: String, toUri: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val moveUp = stringResource(R.string.fix2_library_move_up)
    val moveDown = stringResource(R.string.fix2_library_move_down)
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromUri = from.key as? String ?: return@rememberReorderableLazyListState
        val toUri = to.key as? String ?: return@rememberReorderableLazyListState
        onMove(fromUri, toUri)
    }
    LibraryListDialog(stringResource(R.string.fix2_library_reorder), onDismiss) {
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
            items(playlists, key = { it.uri }) { playlist ->
                ReorderableItem(reorderState, key = playlist.uri) { _ ->
                    val index = playlists.indexOfFirst { it.uri == playlist.uri }
                    val previous = playlists.getOrNull(index - 1)
                    val next = playlists.getOrNull(index + 1)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(playlist.name, Modifier.weight(1f))
                        Box(
                            Modifier.size(44.dp).draggableHandle().semantics {
                                customActions = buildList {
                                    if (previous != null) add(CustomAccessibilityAction(moveUp) {
                                        onMove(playlist.uri, previous.uri)
                                        true
                                    })
                                    if (next != null) add(CustomAccessibilityAction(moveDown) {
                                        onMove(playlist.uri, next.uri)
                                        true
                                    })
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.GripVertical, stringResource(R.string.fix2_library_reorder_item, playlist.name))
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun LibraryOrganizationControls(
    store: LibraryOrganizationStore,
    folder: LibraryFolder?,
    query: String,
    visibleFolders: List<LibraryFolder>,
    movablePlaylists: List<CatalogPlaylist>,
    onOpenFolder: (String?) -> Unit,
    onChooseCustom: () -> Unit,
) {
    var dialog by rememberSaveable(folder?.id) { mutableStateOf<String?>(null) }
    val dismiss = { dialog = null }
    Column {
        if (folder == null) {
            if (query.isBlank()) LibraryChoiceRow(stringResource(R.string.fix2_library_new_folder)) { dialog = "name" }
            visibleFolders.forEach { item -> LibraryChoiceRow(item.name) { onOpenFolder(item.id) } }
        } else {
            LibraryChoiceRow(stringResource(R.string.fix2_library_back)) { onOpenFolder(null) }
            Text(folder.name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            if (query.isBlank()) {
                LibraryChoiceRow(stringResource(R.string.fix2_library_rename_folder)) { dialog = "name" }
                LibraryChoiceRow(stringResource(R.string.fix2_library_delete_folder)) { dialog = "delete" }
            }
        }
        if (query.isBlank() && movablePlaylists.size > 1) {
            LibraryChoiceRow(stringResource(R.string.fix2_library_reorder)) { dialog = "reorder" }
        }
    }
    when (dialog) {
        "name" -> LibraryFolderNameDialog(
            folder,
            onSave = { name ->
                if (folder == null) store.createFolder(name) else store.renameFolder(folder.id, name)
            },
            onDismiss = dismiss,
        )
        "delete" -> if (folder != null) {
            LibraryFolderDeleteDialog(
                folder,
                onDelete = {
                    store.deleteFolder(folder.id)
                    onOpenFolder(null)
                },
                onDismiss = dismiss,
            )
        }
        "reorder" -> PlaylistReorderDialog(
            playlists = movablePlaylists,
            onMove = { from, to ->
                store.reorder(folder?.id, movablePlaylists.map { it.uri }, from, to)
                onChooseCustom()
            },
            onDismiss = dismiss,
        )
    }
}

@Composable
fun LibraryFolderNameDialog(
    folder: LibraryFolder?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(folder?.id) { mutableStateOf(folder?.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (folder == null) R.string.fix2_library_new_folder else R.string.fix2_library_rename_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.fix2_library_folder_name)) },
                singleLine = true,
            )
        },
        dismissButton = { LibraryDialogButton(stringResource(android.R.string.cancel), onDismiss) },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name.trim())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.fix2_library_save)) }
        },
    )
}

@Composable
fun LibraryFolderDeleteDialog(
    folder: LibraryFolder,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fix2_library_delete_named, folder.name)) },
        text = { Text(stringResource(R.string.fix2_library_delete_note)) },
        dismissButton = { LibraryDialogButton(stringResource(android.R.string.cancel), onDismiss) },
        confirmButton = {
            LibraryDialogButton(stringResource(R.string.fix2_library_delete)) {
                onDelete()
                onDismiss()
            }
        },
    )
}

@Composable
fun PlaylistFolderDialog(
    folders: List<LibraryFolder>,
    currentFolderId: String?,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    LibraryListDialog(stringResource(R.string.fix2_library_move_to_folder), onDismiss) {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            if (currentFolderId != null) {
                item {
                    LibraryChoiceRow(stringResource(R.string.library)) {
                        onMove(null)
                        onDismiss()
                    }
                }
            }
            items(folders.filterNot { it.id == currentFolderId }, key = { it.id }) { folder ->
                LibraryChoiceRow(folder.name) {
                    onMove(folder.id)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
fun HidePlaylistTrackItem(onHide: () -> Unit) {
    TrackSheetAction(stringResource(R.string.fix2_library_hide_track), Lucide.X, onClick = onHide)
}

@Composable
fun HiddenPlaylistTracksDialog(
    tracks: List<CatalogTrack>,
    hiddenUris: Set<String>,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LibraryListDialog(stringResource(R.string.fix2_library_hidden_songs), onDismiss) {
        val hidden = tracks.filter { it.uri in hiddenUris }.distinctBy { it.uri }
        if (hidden.isEmpty()) {
            Text(stringResource(R.string.fix2_library_no_hidden))
        } else {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(hidden, key = { it.uri }) { track ->
                    LibraryChoiceRow(stringResource(R.string.fix2_library_show_track, track.name)) { onRestore(track.uri) }
                }
            }
        }
    }
}

@Composable
fun LibraryChoiceRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label)
    }
}

@Composable
private fun LibraryListDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = content,
        confirmButton = { LibraryDialogButton(stringResource(R.string.fix2_library_done), onDismiss) },
    )
}

@Composable
private fun LibraryDialogButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 44.dp)) { Text(label) }
}

@Composable
fun HiddenPlaylistTracksControl(
    tracks: List<CatalogTrack>,
    hiddenUris: Set<String>,
    onRestore: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val restorable = tracks.map { it.uri }.toSet().intersect(hiddenUris)
    if (restorable.isEmpty()) return
    LibraryChoiceRow(stringResource(R.string.fix2_library_hidden_songs)) { open = true }
    if (open) {
        HiddenPlaylistTracksDialog(
            tracks, restorable,
            onRestore = {
                onRestore(it)
                if (restorable.size == 1) open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun MovePlaylistToFolderItem(onMove: () -> Unit) {
    TrackSheetAction(stringResource(R.string.fix2_library_move_to_folder), Lucide.Folder, onClick = onMove)
}
