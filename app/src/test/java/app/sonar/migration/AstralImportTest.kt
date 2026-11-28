package app.sonar.migration

import app.sonar.data.CatalogTrack
import app.sonar.data.ListeningHistory
import app.sonar.data.LocalLibrary
import app.sonar.library.LocalPlaylist
import app.sonar.library.LocalPlaylists
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AstralImportTest {
    @Test
    fun syntheticExportProducesAccountScopedHistoryAndPortablePlaylists() {
        val account = "listener"
        val parked = "files/listen-history/${AstralDataConverters.ownerKey(account)}.jsonl"
        val entries = linkedMapOf(
            "cookies.json" to """[
                {"url":"https://open.spotify.com/","cookie":"sample=default"},
                {"url":"https://open.spotify.com/","cookie":"sample=other","profile":"other"}
            ]""",
            "files/listen_history.owner" to AstralDataConverters.ownerKey("another-listener"),
            "files/listen_history.jsonl" to
                """{"key":"spotify:track:other","at":1744724400000,"title":"Another account","ms":31000}""",
            parked to
                """{"key":"spotify:track:fixture","at":1744724400000,"title":"Song","artist":"Artist","ms":42000}""",
            "files/local_playlists.json" to """{"version":1,"playlists":[{"id":"list","name":"Commute","items":[
                {"key":"item","uri":"content://media/external/audio/media/42","title":"Local song",
                 "artist":"Artist","album":"Album","duration":120000}
            ]}],"additions":{}}""",
        )
        val inventory = entries.keys.joinToString(",") { "\"$it\"" }
        entries["manifest.json"] = """{"formatVersion":1,"appId":"com.music.spotui","appVersion":"1",
            "createdAt":"2025-04-15T13:00:00Z","entries":[$inventory]}"""
        val passphrase = "test passphrase".toCharArray()
        val encrypted = encryptLikeAstral(zip(entries), passphrase)
        val plaintext = SonarImportCrypto.decrypt(encrypted.inputStream(), passphrase)

        val archive = AstralImport.readArchive(plaintext, account, ZoneOffset.UTC)

        assertEquals(1, archive.historyFiles)
        val listen = archive.history.single()
        assertEquals("spotify:track:fixture", listen.track.uri)
        assertEquals(42_000L, listen.listenedMs)
        assertEquals(mapOf("2025-04" to 42_000L), listen.measuredMonths)
        val playlist = archive.playlists!!.single()
        assertEquals("Commute", playlist.name)
        assertEquals("spotify:local:Artist:Album:Local+song:120", playlist.tracks.single().uri)
        assertEquals("sample=default; Path=/; Secure", archive.cookies.values.single().value)
        assertEquals(1, archive.cookies.skippedRecords)
        assertTrue(AstralImport.readArchive(plaintext, null, ZoneOffset.UTC).history.isEmpty())
    }

    @Test
    fun measuredAstralPlayReconcilesWithSpotifyInBothImportOrders() {
        val row = """{"key":"spotify:track:fixture","at":1744724400000,"title":"Song","ms":42000}"""
        val measured = AstralDataConverters.history(row.toByteArray(), ZoneOffset.UTC).single()
        val at = measured.startedAt + 1_000
        val expected = listOf(measured.copy(spotifyAt = at))

        val astralFirst = ListeningHistory.merge(listOf(measured), measured.track, at)
        val spotifyFirst = ListeningHistory.record(ListeningHistory.merge(emptyList(), measured.track, at), measured)

        assertEquals(expected, astralFirst)
        assertEquals(expected, spotifyFirst)
        assertEquals(expected, ListeningHistory.record(astralFirst, measured))
        assertEquals(expected, ListeningHistory.merge(spotifyFirst, measured.track, at))
    }

    @Test
    fun playlistReimportResolvesExistingTracksAfterAudioAccessIsGranted() {
        val deviceTrack = CatalogTrack(
            uri = "local:track:42", name = "Song", artist = "Artist", album = "Album",
            durationMs = 120_000, localFile = "/music/song.mp3",
        )
        val portable = deviceTrack.copy(uri = LocalLibrary.spotifyUri(deviceTrack), localFile = null)
        val playlist = LocalPlaylist("astral-id", "Commute", listOf(portable))
        val original = LocalPlaylists().mergeImported("sonar-id", playlist, emptyList())
        assertEquals(listOf(portable), original.lists.single().tracks)

        val repaired = original.mergeImported("unused-id", playlist, listOf(deviceTrack))

        assertEquals(listOf(LocalPlaylist("sonar-id", "Commute", listOf(deviceTrack))), repaired.lists)
        assertEquals(repaired, repaired.mergeImported("unused-id", playlist, emptyList()))
    }

    @Test
    fun roundTripWithAstralExporter() {
        val original = zip("files/listen_history.jsonl")
        val passphrase = "  café🎵 pass  ".toCharArray()
        val encrypted = encryptLikeAstral(original, passphrase)

        val decrypted = SonarImportCrypto.decrypt(encrypted.inputStream(), passphrase)

        assertArrayEquals(original, decrypted)
        assertEquals(setOf("files/listen_history.jsonl"), AstralImportZip.read(decrypted, { false }) { _, _ -> })
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun wrongPassphraseFailsAuthentication() {
        val encrypted = encryptLikeAstral(zip("cookies.json"), "right password".toCharArray())
        val wrong = "wrong password".toCharArray()

        assertThrows(AEADBadTagException::class.java) {
            SonarImportCrypto.decrypt(encrypted.inputStream(), wrong)
        }
        assertTrue(wrong.all { it == '\u0000' })
    }

    @Test
    fun changedCiphertextFailsAuthentication() {
        val passphrase = "right password".toCharArray()
        val encrypted = encryptLikeAstral(zip("cookies.json"), passphrase)
        encrypted[34] = (encrypted[34].toInt() xor 1).toByte()

        assertThrows(AEADBadTagException::class.java) {
            SonarImportCrypto.decrypt(encrypted.inputStream(), passphrase)
        }
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun rejectsZipSlipAbsolutePathsAndBackslashes() {
        for (name in listOf("files/../outside", "files/nested/../../outside", "/files/outside", "files\\outside")) {
            assertThrows(InvalidAstralExport::class.java) {
                AstralImportZip.read(zip(name), { true }) { _, _ -> error("Unsafe entry reached callback") }
            }
        }
    }

    @Test
    fun rejectsDuplicateNames() {
        val archive = zip("files/first", "files/other")
        // Both names have the same length. Replace the local and central copies.
        val other = "files/other".toByteArray()
        val first = "files/first".toByteArray()
        for (offset in 0..archive.size - other.size) {
            if (other.indices.all { archive[offset + it] == other[it] }) {
                first.copyInto(archive, offset)
            }
        }
        assertThrows(InvalidAstralExport::class.java) {
            AstralImportZip.read(archive, { false }) { _, _ -> }
        }
    }

    @Test
    fun rejectsTruncatedZipDirectory() {
        val archive = zip("cookies.json")
        assertThrows(InvalidAstralExport::class.java) {
            AstralImportZip.read(archive.copyOf(archive.size - 1), { false }) { _, _ -> }
        }
    }

    @Test
    fun rejectsOversizedExpandedMetadataBeforePassingItToImporter() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("cookies.json"))
            val block = ByteArray(1024)
            repeat(4 * 1024 + 1) { zip.write(block) }
            zip.closeEntry()
        }

        assertThrows(AstralExportTooLarge::class.java) {
            AstralImportZip.read(out.toByteArray(), { false }) { _, _ -> error("Oversized entry reached callback") }
        }
    }

    private fun zip(vararg names: String): ByteArray = zip(names.associateWith { "fixture" })

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun encryptLikeAstral(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val spec = PBEKeySpec(passphrase, salt, 210_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        } finally {
            key.fill(0)
        }
        val out = ByteArrayOutputStream()
        out.write("SNMIG1".toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(iv)
        cipher.update(plaintext)?.let { out.write(it) }
        out.write(cipher.doFinal())
        return out.toByteArray()
    }
}
