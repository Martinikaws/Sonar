package app.sonar.backend.youtube

internal data class YouTubeAudioQuality(val itag: Int, val kbps: Int) {
    private val preference: Int
        get() = when (itag) {
            251 -> 2
            141 -> 1
            else -> 0
        }

    companion object {
        fun <T, R : Any> firstPlayable(
            candidates: Iterable<T>,
            isOriginal: (T) -> Boolean,
            order: Comparator<T>,
            resolve: (T) -> R?,
        ): R? = candidates
            .sortedWith(compareByDescending(isOriginal).then(order))
            .firstNotNullOfOrNull(resolve)

        fun order(lowRate: Boolean): Comparator<YouTubeAudioQuality> =
            if (lowRate) compareBy { if (it.kbps > 0) it.kbps else Int.MAX_VALUE }
            else compareByDescending<YouTubeAudioQuality> { it.preference }.thenByDescending { it.kbps }
    }
}
