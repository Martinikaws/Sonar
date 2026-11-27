package app.sonar.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.sonar.R
import app.sonar.ui.theme.LocalLightTheme

@Composable
fun SonarHorizontalLogo(height: Dp, modifier: Modifier = Modifier) {
    SonarLogo(
        R.drawable.logo_sonar_horizontal_light,
        R.drawable.logo_sonar_horizontal_dark,
        modifier.height(height),
    )
}

@Composable
fun SonarStackedLogo(height: Dp, modifier: Modifier = Modifier) {
    SonarLogo(
        R.drawable.logo_sonar_stacked_light,
        R.drawable.logo_sonar_stacked_dark,
        modifier.height(height),
    )
}

@Composable
private fun SonarLogo(@DrawableRes light: Int, @DrawableRes dark: Int, modifier: Modifier) {
    Image(
        painter = painterResource(if (LocalLightTheme.current) light else dark),
        contentDescription = stringResource(R.string.wire2_sonar_logo),
        modifier = modifier,
    )
}
