package app.sonar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sonar.ui.glass.backdrop.Backdrop
import app.sonar.R
import app.sonar.ui.glass.pressable
import app.sonar.ui.player.GlassSurface
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The one thing an expired Web API session should cost the listener: a tap.
 *
 * Spotify's own application, the one search and the home feed are metered
 * against, holds a token that lapses on its own schedule, and until now the only
 * sign of it was a search that quietly answered nothing and a settings screen
 * the user had to think to open. The client id is already stored and does not
 * expire, so the way back is the same OAuth flow with nothing to type: this says
 * what happened and runs it.
 */
@Composable
fun SessionExpiredNotice(
    visible: Boolean,
    backdrop: Backdrop,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: Int = R.string.web_api_expired_title,
    body: Int = R.string.web_api_expired_body,
    action: Int? = R.string.web_api_expired_action,
    dismiss: Int = R.string.later,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        GlassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            surfaceColor = Color.White.copy(alpha = 0.10f),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (action != null) {
                        NoticeAction(action, onReconnect, primary = true)
                    }
                    NoticeAction(dismiss, onDismiss, primary = false)
                }
            }
        }
    }
}

@Composable
private fun NoticeAction(label: Int, onClick: () -> Unit, primary: Boolean) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).pressable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (primary) FontWeight.SemiBold else null,
            color = Color.White.copy(alpha = if (primary) 1f else 0.6f),
        )
    }
}
