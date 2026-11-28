package app.sonar.playback.automix

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sonar.R
import app.sonar.SonarApplication
import kotlin.math.roundToLong

@Composable
internal fun AutoMixSettings() {
    val context = LocalContext.current
    val state by AutoMix.state.collectAsStateWithLifecycle()
    val store = (context.applicationContext as SonarApplication).crossfade
    val crossfade by store.seconds.collectAsStateWithLifecycle()
    LaunchedEffect(context, crossfade) { AutoMix.refresh(context) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        MixSwitch(R.string.automix_title, state.enabled) { AutoMix.setEnabled(context, it) }
        if (state.enabled) {
            MixSwitch(R.string.automix_beat_match, state.beatMatch) { AutoMix.setBeatMatch(context, it) }
            when {
                crossfade == 0 -> Text(stringResource(R.string.automix_crossfade_required))
                state.pair == null -> Text(stringResource(R.string.automix_no_pair))
                else -> TransitionEditor(state)
            }
        }
        if (state.cachedTracks > 0) {
            TextButton(
                onClick = { AutoMix.clearAnalysis(context) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.automix_clear_analysis, state.cachedTracks)) }
        }
        if (state.enabled) {
            val title = stringResource(R.string.automix_title)
            val summary = stringResource(R.string.automix_diagnostics, state.cachedTracks, state.outBars.size, state.inBars.size)
            TextButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(title, summary))
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.automix_copy_diagnostics)) }
        }
    }
}

@Composable
private fun MixSwitch(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun TransitionEditor(state: AutoMix.State) {
    val context = LocalContext.current
    val pair = state.pair ?: return
    val initial = state.saved ?: state.suggested
    var expanded by remember(pair.key) { mutableStateOf(false) }
    when {
        state.loading -> Text(stringResource(R.string.automix_loading))
        state.unavailable -> Text(stringResource(R.string.automix_analysis_unavailable))
    }
    if (initial == null || !initial.fits(state.outLength, state.inLength)) {
        if (!state.loading) Text(stringResource(R.string.automix_no_timing))
        return
    }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(if (expanded) R.string.automix_close_editor else R.string.automix_editor))
    }
    if (!expanded) return

    // TODO: Use a separate two-deck PCM transport so preview leaves the playback queue alone.
    var edit by remember(pair.key, state.saved, state.outLength, state.inLength) {
        mutableStateOf<TransitionTiming?>(null)
    }
    val timing = edit ?: initial
    val incomingOverlap = kotlin.math.ceil(timing.fadeMs * timing.speedIn.toDouble()).toLong()
    val outBounds = 1L..(state.outLength - timing.fadeMs)
    val inBounds = 0L..(state.inLength - incomingOverlap)
    val fadeBounds = 1L..minOf(
        12_000L, state.outLength - timing.outMs, ((state.inLength - timing.inMs) / timing.speedIn.toDouble()).toLong(),
    )

    Text(stringResource(R.string.automix_pair, pair.outgoing, pair.incoming))
    CueTimeline(state.outBars, timing.outMs, state.outLength)
    TimingSlider(R.string.automix_out_cue, timing.outMs, outBounds) { edit = timing.copy(outMs = it) }
    CueTimeline(state.inBars, timing.inMs, state.inLength)
    TimingSlider(R.string.automix_in_cue, timing.inMs, inBounds) { edit = timing.copy(inMs = it) }
    TimingSlider(R.string.automix_overlap, timing.fadeMs, fadeBounds) { edit = timing.copy(fadeMs = it) }
    if (state.outBars.isNotEmpty() && state.inBars.isNotEmpty()) {
        TextButton(onClick = {
            edit = timing.copy(
                outMs = TransitionPlanner.snap(timing.outMs, state.outBars.toLongArray(), outBounds),
                inMs = TransitionPlanner.snap(timing.inMs, state.inBars.toLongArray(), inBounds),
            )
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.automix_snap)) }
    }
    TextButton(onClick = { AutoMix.save(context, pair.key, timing) }, modifier = Modifier.heightIn(min = 48.dp),
        enabled = edit != null && timing != state.saved && timing.fits(state.outLength, state.inLength),
    ) { Text(stringResource(R.string.automix_save)) }
    if (state.saved != null || edit != null) {
        TextButton(onClick = {
            edit = null
            AutoMix.save(context, pair.key, null)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.automix_reset)) }
    }
}

@Composable
private fun CueTimeline(bars: List<Long>, cue: Long, duration: Long) {
    if (bars.isEmpty() || duration <= 0) return
    val ticks = MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        for (bar in bars) {
            if (bar !in 0L..duration) continue
            val x = size.width * (bar.toDouble() / duration).toFloat()
            drawLine(ticks, Offset(x, size.height / 4), Offset(x, size.height * 3 / 4))
        }
        val x = size.width * (cue.toDouble() / duration).toFloat()
        drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun TimingSlider(label: Int, value: Long, bounds: LongRange, onChange: (Long) -> Unit) {
    val title = stringResource(label)
    Text(stringResource(R.string.automix_timing, title, value / 1000.0))
    if (bounds.last <= bounds.first) return
    Slider(
        value = value.toFloat().coerceIn(bounds.first.toFloat(), bounds.last.toFloat()),
        onValueChange = { onChange(it.roundToLong().coerceIn(bounds)) },
        valueRange = bounds.first.toFloat()..bounds.last.toFloat(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = title },
    )
    Row {
        TextButton(
            onClick = { onChange((value - 100).coerceIn(bounds)) },
            enabled = value > bounds.first,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.automix_nudge_back)) }
        TextButton(
            onClick = { onChange((value + 100).coerceIn(bounds)) },
            enabled = value < bounds.last,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.automix_nudge_forward)) }
    }
}
