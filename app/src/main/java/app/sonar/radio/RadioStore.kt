package app.sonar.radio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /** Reads the saved lists once; the file is small, so it is read where it is first needed. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "radio.json")
        file = f
        data = RadioData.decode(runCatching { f.readText() }.getOrNull())
    }

    fun isFavorite(uuid: String) = data.favorites.any { it.uuid == uuid }
    fun toggle(station: RadioStation) = save(data.toggled(station))
    fun played(station: RadioStation) = save(data.played(station))

    @Synchronized
    private fun save(next: RadioData) {
        data = next
        val f = file ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.writeText(RadioData.encode(next))
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
