package app.sonar.radio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class RadioData(
    val favorites: List<RadioStation> = emptyList(),
    /** Newest first. */
    val recents: List<RadioStation> = emptyList(),
) {
    fun toggled(station: RadioStation): RadioData =
        if (favorites.any { it.uuid == station.uuid }) copy(favorites = favorites.filterNot { it.uuid == station.uuid })
        else if (RadioParsing.playable(station.url)) copy(favorites = listOf(station) + favorites)
        else this

    fun played(station: RadioStation, max: Int = MAX_RECENTS): RadioData =
        copy(recents = (listOf(station) + recents.filterNot { it.uuid == station.uuid }).take(max))

    companion object {
        const val MAX_RECENTS = 10
        private val json = Json { ignoreUnknownKeys = true }

        /** What a damaged or missing file reads as: nothing saved. */
        fun decode(text: String?): RadioData =
            text?.let { runCatching { json.decodeFromString<RadioData>(it) }.getOrNull() } ?: RadioData()

        fun encode(data: RadioData): String = json.encodeToString(serializer(), data)
    }
}

/** Favourites and recent stations, kept on this phone only (the directory has no account to sync them to). */
object RadioStore {
    var data by mutableStateOf(RadioData())
        private set
    private var file: File? = null
    private val lock = Mutex()

    suspend fun init(context: Context) = lock.withLock {
        if (file != null) return@withLock
        val f = File(context.filesDir, "radio.json")
        data = withContext(Dispatchers.IO) {
            RadioData.decode(runCatching { f.readText() }.getOrNull())
        }
        file = f
    }

    fun isFavorite(uuid: String) = data.favorites.any { it.uuid == uuid }
    suspend fun toggle(station: RadioStation) = save { it.toggled(station) }
    suspend fun played(station: RadioStation) = save { it.played(station) }

    private suspend fun save(update: (RadioData) -> RadioData) = lock.withLock {
        val f = file ?: return@withLock
        val next = update(data)
        data = next
        withContext(Dispatchers.IO + NonCancellable) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            runCatching {
                tmp.writeText(RadioData.encode(next))
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Unit
    }
}
