package app.sonar.parity.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionDownloadTest {
    @Test
    fun acceptsHttpsDownloadWithQuery() {
        val url = extensionPackageUrl(" https://example.org/extension.zip?version=2 ")
        assertEquals("https://example.org/extension.zip?version=2", url.toString())
    }

    @Test
    fun rejectsNonHttpsCredentialsAndFragments() {
        assertNull(extensionPackageUrl("http://example.org/extension.zip"))
        assertNull(extensionPackageUrl("https://user:pass@example.org/extension.zip"))
        assertNull(extensionPackageUrl("https://example.org/extension.zip#section"))
    }
}
