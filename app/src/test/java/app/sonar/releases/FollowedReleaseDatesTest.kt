package app.sonar.releases

import app.sonar.data.AlbumDto
import app.sonar.data.recentReleases
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowedReleaseDatesTest {
    @Test fun includesBothBoundaryDaysAndHandlesImpreciseDatesConservatively() {
        val today = LocalDate.of(2026, 4, 15)
        val albums = listOf(
            release("expired", "2025-04-14"),
            release("cutoff", "2025-04-15"),
            release("old-month", "2025-04"),
            release("new-month", "2026-04"),
            release("today", "2026-04-15"),
            release("future", "2026-04-16"),
        )

        assertEquals(
            listOf("today", "new-month", "cutoff"),
            recentReleases(albums, today.minusMonths(12), today).map { it.id },
        )
    }

    private fun release(id: String, date: String) = AlbumDto(id = id, name = id, releaseDate = date)
}
