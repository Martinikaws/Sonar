package app.sonar.ui.look

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sonar.R
import app.sonar.data.LyricLine
import app.sonar.data.LyricWord
import kotlin.math.roundToLong

@Composable
fun LookPicker(modifier: Modifier = Modifier, store: LookStore = rememberLookStore()) {
    val look by store.state.collectAsStateWithLifecycle()
    Column(modifier) {
        LookPreview(look)
        Column(Modifier.selectableGroup()) {
            for (option in Look.entries) {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = option == look,
                            onClick = { store.select(option) },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = option == look, onClick = null)
                    Text(stringResource(option.label))
                }
            }
        }
    }
}

@Composable
fun LookPreview(look: Look, modifier: Modifier = Modifier) {
    val reducedMotion = rememberLookReducedMotion()
    val sample = stringResource(R.string.look_sample)
    val next = stringResource(R.string.look_sample_next)
    val line = remember(sample) {
        LyricLine(
            startTimeMs = 0L,
            text = sample,
            words = sample.split(' ').mapIndexed { index, word ->
                LyricWord(index * 600L, (index + 1) * 600L, word)
            },
        )
    }
    var position by remember { mutableFloatStateOf(1_200f) }
    val style = MaterialTheme.typography.headlineSmall
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black),
        ) {
            LookBackdrop(look, Modifier.matchParentSize(), reducedMotion = reducedMotion)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SweptLyricLine(
                    line = line,
                    positionMs = { position.roundToLong() },
                    style = style,
                    color = Color.White,
                    reducedMotion = reducedMotion || !look.usesSweep,
                )
                Text(
                    next,
                    color = Color.White.copy(alpha = if (look == Look.CLASSIC) 0.5f else LookNextLineAlpha),
                    style = style,
                )
            }
        }
        if (look.usesSweep && !reducedMotion) {
            val label = stringResource(R.string.look_preview_position)
            Slider(
                value = position,
                onValueChange = { position = it },
                valueRange = 0f..line.words.last().endMs.toFloat(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = label },
            )
        }
    }
}
