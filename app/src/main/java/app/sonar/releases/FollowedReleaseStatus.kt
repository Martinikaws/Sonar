package app.sonar.releases

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.ui.MainViewModel
import app.sonar.ui.theme.InkDim

@Composable
internal fun FollowedReleaseStatus(
    state: MainViewModel.FollowedReleaseState,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    val message = when {
        state.loading && state.albums.isEmpty() -> stringResource(R.string.followed_loading)
        state.loading -> null
        state.error != null -> state.error
        state.failedArtists > 0 -> stringResource(R.string.followed_releases_partial)
        state.loaded && state.artistCount == 0 -> stringResource(R.string.followed_releases_no_artists)
        state.loaded && state.albums.isEmpty() -> stringResource(R.string.followed_releases_empty)
        else -> null
    } ?: return

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(message, color = InkDim)
        if (!state.loading && state.needsSpotifyConnection) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
        } else if (!state.loading && (state.error != null || state.failedArtists > 0)) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}
