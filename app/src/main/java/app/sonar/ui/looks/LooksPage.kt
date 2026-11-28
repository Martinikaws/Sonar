package app.sonar.ui.looks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sonar.R
import app.sonar.data.PreferencesStore
import app.sonar.data.PreferencesStore.LooksOption
import app.sonar.ui.settings.PRESET_HUES
import app.sonar.ui.settings.PlainToggle
import app.sonar.ui.settings.Swatch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LooksPage(
    preferences: PreferencesStore,
    contentPadding: PaddingValues,
    header: @Composable () -> Unit,
) {
    val looks by preferences.looks.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { header() }
        item {
            LooksSwitch(R.string.looks_cover_colors, LooksOption.CoverColors, looks, preferences)
            TextButton(
                onClick = { draft = looks.accent },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            ) {
                Text(
                    stringResource(R.string.looks_accent),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AccentSwatch(looksAccentArgb(looks.accent))
            }
            LooksSwitch(R.string.looks_spin_cover, LooksOption.SpinCover, looks, preferences)
            LooksSwitch(R.string.looks_shuffle, LooksOption.Shuffle, looks, preferences)
            LooksSwitch(R.string.looks_repeat, LooksOption.Repeat, looks, preferences)
            LooksSwitch(R.string.looks_lyrics, LooksOption.Lyrics, looks, preferences)
            LooksSwitch(R.string.looks_source, LooksOption.Source, looks, preferences)
        }
    }

    draft?.let { value ->
        val argb = looksAccentArgb(value)
        val valid = value.isBlank() || argb != null
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text(stringResource(R.string.looks_accent)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FlowRow(Modifier.selectableGroup()) {
                        PRESET_HUES.forEach { (hue, label) ->
                            val color = Color.hsv(hue, 0.8f, 1f)
                            val preset = color.toArgb()
                            Swatch(stringResource(label), color, argb == preset) {
                                draft = "#" + (preset and 0xFFFFFF).toString(16).padStart(6, '0')
                            }
                        }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.looks_hex)) },
                        supportingText = {
                            Text(stringResource(if (valid) R.string.looks_hex_help else R.string.looks_hex_invalid))
                        },
                        trailingIcon = { AccentSwatch(argb) },
                        singleLine = true,
                        isError = !valid,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        preferences.setLooksAccent(value.trim())
                        draft = null
                    },
                ) { Text(stringResource(R.string.looks_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { draft = null }) { Text(stringResource(R.string.looks_cancel)) }
            },
        )
    }
}

@Composable
private fun AccentSwatch(argb: Int?) {
    Box(
        Modifier.size(20.dp).background(
            argb?.let { Color(it) } ?: app.sonar.ui.theme.DefaultAccent,
            CircleShape,
        ),
    )
}

@Composable
private fun LooksSwitch(
    label: Int,
    option: LooksOption,
    looks: PreferencesStore.Looks,
    preferences: PreferencesStore,
) {
    val checked = option in looks
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch) { preferences.setLooksOption(option, it) }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        PlainToggle(checked)
    }
}
