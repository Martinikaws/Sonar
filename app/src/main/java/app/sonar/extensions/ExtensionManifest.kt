package app.sonar.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val versionCode: Int = 1,
    val apiVersion: Int = 1,
    val description: String = "",
    val main: String = "main.js",
    val icon: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val fileName = Regex("[a-zA-Z0-9_][a-zA-Z0-9_.-]*")

        fun validId(id: String): Boolean =
            id.length <= 160 && id.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+"))

        fun parse(text: String): ExtensionManifest =
            json.decodeFromString<ExtensionManifest>(text).also {
                require(validId(it.id)) { "Invalid extension ID" }
                require(it.name.isNotBlank() && it.name.length <= 40) { "Invalid extension name" }
                require(it.author.isNotBlank() && it.author.length <= 60) { "Invalid extension author" }
                require(it.version.isNotBlank() && it.version.length <= 20 && it.versionCode > 0) {
                    "Invalid extension version"
                }
                require(it.apiVersion == 1) { "Unsupported extension API version" }
                require(it.description.length <= 500) { "Invalid extension description" }
                require(validFile(it.main, ".js")) { "Invalid entry point" }
                require(it.icon == null || validFile(it.icon, ".png")) { "Invalid icon path" }
            }

        private fun validFile(name: String, suffix: String): Boolean =
            name.length <= 160 && fileName.matches(name) && ".." !in name && name.endsWith(suffix)
    }
}
