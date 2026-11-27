package app.sonar.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.data.CatalogTrack
import app.sonar.flac.FlacField
import app.sonar.flac.FlacProvider
import app.sonar.flac.FlacSources
import app.sonar.flac.archiveId
import app.sonar.flac.flacServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val FlacProvider.label: Int
    get() = when (this) {
        FlacProvider.JAMENDO -> R.string.flac_jamendo
        FlacProvider.NAVIDROME -> R.string.flac_navidrome
        FlacProvider.ARCHIVE -> R.string.flac_archive
        FlacProvider.BANDCAMP -> R.string.flac_bandcamp
        FlacProvider.MASSIVE -> R.string.flac_massive
    }

@Composable
internal fun FlacProviderSettings(
    provider: FlacProvider,
    sources: FlacSources,
    onPlay: (List<CatalogTrack>, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var enabled by remember(provider, sources) { mutableStateOf(sources.enabled(provider)) }
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<CatalogTrack>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val massive = provider == FlacProvider.MASSIVE
    var fields by remember(provider, sources) {
        val keys = when (provider) {
            FlacProvider.JAMENDO -> listOf(FlacField.CLIENT)
            FlacProvider.NAVIDROME -> listOf(FlacField.SERVER, FlacField.USER, FlacField.PASSWORD)
            FlacProvider.ARCHIVE -> listOf(FlacField.RECORDING)
            FlacProvider.BANDCAMP -> emptyList()
            FlacProvider.MASSIVE -> listOf(FlacField.PARTNER_KEY)
        }
        mutableStateOf(keys.associateWith(sources::field))
    }
    val configured = when (provider) {
        FlacProvider.JAMENDO -> FlacSources.validClient(fields.getValue(FlacField.CLIENT))
        FlacProvider.NAVIDROME -> flacServer(fields.getValue(FlacField.SERVER)) != null &&
            fields.getValue(FlacField.USER).isNotBlank() && fields.getValue(FlacField.PASSWORD).isNotEmpty()
        FlacProvider.ARCHIVE -> archiveId(fields.getValue(FlacField.RECORDING)) != null
        FlacProvider.BANDCAMP -> true
        FlacProvider.MASSIVE -> fields.getValue(FlacField.PARTNER_KEY).isNotBlank()
    }
    val search: () -> Unit = {
        busy = true
        failed = false
        tracks = null
        scope.launch {
            try {
                tracks = sources.search(provider, query.trim())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isNotEmpty()) {
            busy = true
            failed = false
            tracks = null
            scope.launch {
                try {
                    if (sources.importFiles(selected) == 0) failed = true
                    else tracks = sources.search(provider, "")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .toggleable(value = enabled, enabled = !busy && (!massive || configured), role = Role.Switch) {
                    sources.setEnabled(provider, it)
                    enabled = it
                    tracks = null
                    failed = false
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(provider.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled, onCheckedChange = null, enabled = !busy && (!massive || configured))
        }
        fields.forEach { (field, value) ->
            val label = when (field) {
                FlacField.CLIENT -> R.string.flac_client_id
                FlacField.SERVER -> R.string.flac_server
                FlacField.USER -> R.string.flac_username
                FlacField.PASSWORD -> R.string.flac_password
                FlacField.RECORDING -> R.string.flac_recording
                FlacField.PARTNER_KEY -> R.string.flac_partner_key
            }
            val secret = field in setOf(FlacField.CLIENT, FlacField.PASSWORD, FlacField.PARTNER_KEY)
            OutlinedTextField(
                value = value,
                onValueChange = {
                    fields = fields + (field to it)
                    sources.setField(field, it)
                    if (massive && it.isBlank()) {
                        sources.setEnabled(provider, false)
                        enabled = false
                    }
                    tracks = null
                    failed = false
                },
                label = { Text(stringResource(label)) },
                enabled = !busy,
                singleLine = true,
                isError = value.isNotBlank() && when (field) {
                    FlacField.SERVER -> flacServer(value) == null
                    FlacField.RECORDING -> archiveId(value) == null
                    else -> false
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (secret) androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ) else androidx.compose.foundation.text.KeyboardOptions.Default,
            )
        }

        if (massive) {
            val links = LocalUriHandler.current
            Text(stringResource(R.string.flac_massive_description), style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = { runCatching { links.openUri("https://docs.massivemusic.com/reference/introduction") } },
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.flac_massive_access)) }
        } else if (enabled) {
            val imported = provider == FlacProvider.BANDCAMP
            val archive = provider == FlacProvider.ARCHIVE
            if (imported) {
                TextButton(
                    onClick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(stringResource(R.string.flac_import)) }
            } else if (!archive) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.flac_search_query)) },
                    enabled = !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                )
            }
            TextButton(
                enabled = configured && !busy && (archive || imported || query.isNotBlank()),
                onClick = search,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(stringResource(when {
                    archive -> R.string.flac_load_recording
                    imported -> R.string.flac_show_imports
                    else -> R.string.flac_search
                }))
            }
            if (busy) Text(stringResource(R.string.flac_loading))
            if (failed) Text(stringResource(R.string.flac_error))
            if (tracks?.isEmpty() == true) Text(stringResource(R.string.flac_empty))
            tracks?.let { results ->
                results.forEachIndexed { index, track ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.flac_play)) {
                                onPlay(results, index)
                            }.padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(track.name, style = MaterialTheme.typography.bodyLarge)
                        if (track.artist.isNotBlank()) Text(track.artist, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        HorizontalDivider()
    }
}
