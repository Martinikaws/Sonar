package app.sonar.update

import java.io.IOException

internal const val MAX_UPDATE_BYTES = 200L * 1024 * 1024

internal fun requireUpdateSize(bytes: Long) {
    if (bytes > MAX_UPDATE_BYTES) throw IOException("Update exceeds 200 MiB")
}

internal fun selectSonarAsset(version: String, names: Collection<String>, supportedAbis: List<String>): String? =
    (supportedAbis + "universal").firstNotNullOfOrNull { abi ->
        "sonar-$version-$abi.apk".takeIf { it in names }
    }
