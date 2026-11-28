package app.sonar.ui.adaptive

internal enum class AdaptiveWidth {
    Compact,
    Medium,
    Expanded,
}

internal fun adaptiveWidth(widthDp: Int): AdaptiveWidth = when {
    widthDp >= 840 -> AdaptiveWidth.Expanded
    widthDp >= 600 -> AdaptiveWidth.Medium
    else -> AdaptiveWidth.Compact
}
