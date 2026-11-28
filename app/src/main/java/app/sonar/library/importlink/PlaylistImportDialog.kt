package app.sonar.library.importlink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.SonarApplication
import app.sonar.backend.BackendId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PlaylistImportDialog(container: SonarApplication, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var imported by rememberSaveable { mutableStateOf<Int?>(null) }
    var missing by rememberSaveable { mutableStateOf(0) }
    var source by rememberSaveable { mutableStateOf<BackendId?>(null) }
    val backend by container.preferences.backend.collectAsState()
    val scope = rememberCoroutineScope()
    val importedCount = imported
    val errorMessage = error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            if (importedCount != null) {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(R.string.import_result, importedCount, missing))
                    if (importedCount > 0 && source != null && source != backend) {
                        val provider = if (source == BackendId.SPOTIFY) R.string.backend_spotify
                        else R.string.backend_youtube
                        Text(stringResource(R.string.import_playback_source, stringResource(provider)))
                    }
                }
            } else {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.import_link)) },
                        supportingText = if (errorMessage == null) null else {
                            { Text(stringResource(errorMessage), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        },
                        isError = errorMessage != null,
                        enabled = !loading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.import_loading),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        },
        dismissButton = {
            if (importedCount == null) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
        confirmButton = {
            if (importedCount != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(stringResource(R.string.fix2_library_done))
                }
            } else {
                TextButton(
                    enabled = input.isNotBlank() && !loading,
                    modifier = Modifier.heightIn(min = 44.dp),
                    onClick = {
                        val link = parsePlaylistLink(input)
                        error = when (link) {
                            PlaylistLink.Invalid -> R.string.import_invalid
                            PlaylistLink.Unsupported -> R.string.import_unsupported
                            else -> null
                        }
                        if (error == null && !loading) {
                            loading = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        val playlist = loadPlaylist(container, link)
                                        ensureActive()
                                        container.localPlaylists.create(playlist.name, playlist.tracks)
                                        playlist
                                    }
                                    imported = result.tracks.distinctBy { it.uri }.size
                                    missing = result.missing
                                    source = if (link is PlaylistLink.Spotify) BackendId.SPOTIFY
                                    else BackendId.YOUTUBE_MUSIC
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (_: Exception) {
                                    error = if (link is PlaylistLink.Spotify && !container.webApi.isReady) {
                                        R.string.import_spotify_error
                                    } else {
                                        R.string.import_error
                                    }
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.import_action)) }
            }
        },
    )
}
