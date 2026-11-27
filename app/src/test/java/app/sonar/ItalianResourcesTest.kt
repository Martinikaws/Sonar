package app.sonar

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ItalianResourcesTest {
    @Test
    fun italianMatchesTranslatableEnglishResources() {
        val res = sequenceOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
        val english = readResources(res.resolve("values"))
        val italian = readResources(res.resolve("values-it"))
        val errors = mutableListOf<String>()

        for ((key, source) in english) {
            val translation = italian[key]
            when {
                translation == null -> errors += "Missing Italian ${key.first} ${key.second}"
                itemPlaceholders(source) != itemPlaceholders(translation) ->
                    errors += "Items or placeholders differ for ${key.first} ${key.second}"
            }
        }

        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    private fun readResources(directory: File): Map<Pair<String, String>, Element> {
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return buildMap {
            for (file in directory.listFiles().orEmpty().filter { it.extension == "xml" }) {
                val root = parser.parse(file).documentElement
                for (element in root.children()) {
                    if (element.tagName !in resourceTypes) continue
                    if (element.getAttribute("translatable") == "false") continue
                    val key = element.tagName to element.getAttribute("name")
                    check(put(key, element) == null) { "Duplicate resource $key in $directory" }
                }
            }
        }
    }

    private fun itemPlaceholders(element: Element): Map<String, Set<String>> {
        val items = if (element.tagName == "string") listOf(element) else element.children()
        return items.mapIndexed { index, item ->
            val key = if (element.tagName == "plurals") item.getAttribute("quantity") else index.toString()
            val tokens = placeholder.findAll(item.textContent)
                .map { it.value }
                .filter { it != "%%" }
                .toSet()
            key to tokens
        }.toMap()
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private companion object {
        val resourceTypes = setOf("string", "plurals", "string-array")
        val placeholder = Regex("""%%|%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[a-zA-Z])""")
    }
}
