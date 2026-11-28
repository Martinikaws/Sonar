package app.sonar.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sonar.ui.Routes

@Composable
internal fun adaptiveLayout(
    route: String?,
    previousRoute: String?,
    chromeHidden: Boolean,
): AdaptiveLayout {
    val width = adaptiveWidth(LocalConfiguration.current.screenWidthDp)
    val direction = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    return AdaptiveLayout(
        width, route, previousRoute, chromeHidden,
        safeDrawing.calculateStartPadding(direction),
        safeDrawing.calculateEndPadding(direction),
        remember { mutableStateOf(0.dp) },
    )
}

internal class AdaptiveLayout(
    width: AdaptiveWidth,
    route: String?,
    previousRoute: String?,
    chromeHidden: Boolean,
    startInset: Dp,
    endInset: Dp,
    private val controlsHeight: MutableState<Dp>,
) {
    val sideNavigation = width != AdaptiveWidth.Compact
    val showRail = sideNavigation && !chromeHidden
    val expandedLibrary = width == AdaptiveWidth.Expanded
    private val splitLibrary = expandedLibrary && route == Routes.PLAYLIST && previousRoute == Routes.LIBRARY
    private val showLibrary = expandedLibrary && (route == Routes.LIBRARY || splitLibrary)

    val railModifier = Modifier.padding(start = startInset)
    private val paneInsets = Modifier.padding(
        start = if (showRail) TabletRailWidth + startInset else 0.dp,
        end = if (sideNavigation) endInset else 0.dp,
    )
    val pageModifier = if (sideNavigation && route == Routes.SEARCH) paneInsets.imePadding() else paneInsets
    val destinationModifier = if (splitLibrary) {
        Modifier.padding(start = TabletLibraryWidth).clipToBounds()
    } else {
        Modifier
    }

    fun bottomControlsModifier(landscape: Boolean): Modifier =
        paneInsets.then(if (landscape || sideNavigation) Modifier.widthIn(max = 640.dp) else Modifier.fillMaxWidth())

    fun bottomPadding(navBar: Dp, controlsVisible: Boolean, phonePadding: Dp): Dp =
        if (sideNavigation) navBar + (if (controlsVisible) controlsHeight.value else 0.dp) else phonePadding

    fun onControlsHeightChanged(height: Dp) {
        controlsHeight.value = height
    }

    @Composable
    fun LibraryPane(content: @Composable () -> Unit) {
        if (showLibrary) {
            Box(
                if (splitLibrary) Modifier.width(TabletLibraryWidth).fillMaxHeight().clipToBounds()
                else Modifier.fillMaxSize(),
            ) { content() }
        }
    }
}
