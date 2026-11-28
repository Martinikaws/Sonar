package app.sonar.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.ui.Routes
import app.sonar.ui.theme.CappedFontScale
import com.composables.icons.lucide.Grid2x2
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Music2
import com.composables.icons.lucide.Radio
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X

internal val TabletRailWidth = 88.dp
internal val TabletLibraryWidth = 384.dp

private val destinations = listOf(
    Triple(Routes.HOME, R.string.home, Lucide.House),
    Triple(Routes.NEW, R.string.tab_new, Lucide.Grid2x2),
    Triple(Routes.RADIO, R.string.tab_radio, Lucide.Radio),
    Triple(Routes.LIBRARY, R.string.library, Lucide.Music2),
    Triple(Routes.SEARCH, R.string.search, Lucide.Search),
)

@Composable
internal fun AdaptiveLayout.TabletRail(
    activeTab: String,
    showSearchField: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    NavigationRail(
        modifier = modifier.then(railModifier).width(TabletRailWidth),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for ((route, title, icon) in destinations) {
                NavigationRailItem(
                    selected = route == activeTab,
                    onClick = {
                        if (route == Routes.SEARCH) {
                            if (showSearchField) {
                                onSearchOpenChange(false)
                                focus.clearFocus()
                                keyboard?.hide()
                                return@NavigationRailItem
                            }
                            onSearchOpenChange(true)
                        }
                        onSelect(route)
                    },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(stringResource(title)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun AdaptiveLayout.BottomControls(
    showSearch: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    margin: Dp,
    bottomInset: Dp,
    miniPlayer: (@Composable (Modifier) -> Unit)?,
    phoneControls: @Composable () -> Unit,
) {
    if (sideNavigation) {
        val density = LocalDensity.current
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = margin, end = margin, bottom = bottomInset)
                .onSizeChanged { onControlsHeightChanged(with(density) { it.height.toDp() }) }
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showSearch) TabletSearchField(query, onQuery)
            CappedFontScale { miniPlayer?.invoke(Modifier) }
        }
    } else {
        CappedFontScale { phoneControls() }
    }
}

@Composable
private fun TabletSearchField(query: String, onQuery: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Surface {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(stringResource(R.string.search)) },
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { onQuery("") },
                        modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp),
                    ) {
                        Icon(Lucide.X, contentDescription = stringResource(R.string.clear))
                    }
                }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
    }
}
