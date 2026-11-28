package app.sonar.home

import app.sonar.R

internal enum class HomeFilter(val label: Int, val facet: String? = null) {
    ALL(R.string.feed_all),
    MUSIC(R.string.home_music, "music-chip"),
    PODCASTS(R.string.podcasts, "podcasts-chip"),
}

