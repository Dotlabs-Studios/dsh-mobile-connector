package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The two languages, held against each other.
 *
 * This is the test that makes "add a language" a safe operation rather than a
 * hopeful one. A missing translation does not crash and does not even look
 * broken: Android answers with the base language, so the screen simply shows one
 * Italian sentence in the middle of an English one, and nothing anywhere says so.
 * The only cheap moment to notice is here, on the JVM, before the APK exists.
 *
 * What it checks, and why each one is worth a test:
 *
 * - **every key exists in both**, which is the whole of "the translation is
 *   complete" as far as a machine can tell;
 * - **no key exists in only one**, because an English row nobody uses is a
 *   sentence that was translated and then orphaned by a rename, and the next
 *   person to touch it will assume it is live;
 * - **the placeholders match**, which is the difference between a sentence and a
 *   crash: `%1$s` dropped in translation is an `IllegalFormatException` on a
 *   phone, in the one language the author does not read;
 * - **no value is empty or whitespace**, because an empty string renders as a
 *   blank line where a sentence should be, and every one of those is a bug
 *   somebody has to find by looking at the screen;
 * - **the escapes are legal**, since a stray apostrophe is not a translation
 *   problem but a build failure, and it is cheap to say which key did it.
 */
class TranslationParityTest {

    /** One resource, as much of it as this test cares about. */
    private data class Resource(
        val kind: String,
        /** The text of a `<string>`, or one form of a `<plurals>`. */
        val values: List<String>,
    )

    @Test
    fun `both languages define exactly the same keys`() {
        val base = resources("values")
        val english = resources("values-en")

        val missingInEnglish = (base.keys - english.keys).sorted()
        val orphanedInEnglish = (english.keys - base.keys).sorted()

        assertEquals("keys with no English translation", emptyList<String>(), missingInEnglish)
        assertEquals("English keys no longer in the base language", emptyList<String>(), orphanedInEnglish)
        assertTrue("the tables look empty", base.size > 100)
    }

    @Test
    fun `a plural is a plural in both languages`() {
        val base = resources("values")
        val english = resources("values-en")

        base.forEach { (key, resource) ->
            val other = english[key] ?: return@forEach
            assertEquals("`$key` is a <${resource.kind}> in one language and a <${other.kind}> in the other", resource.kind, other.kind)
        }
    }

    @Test
    fun `no sentence is empty`() {
        listOf("values", "values-en").forEach { folder ->
            resources(folder).forEach { (key, resource) ->
                resource.values.forEach { value ->
                    assertTrue("`$key` in $folder is blank", value.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `every placeholder survives translation`() {
        val base = resources("values")
        val english = resources("values-en")

        base.forEach { (key, resource) ->
            val other = english[key] ?: return@forEach
            assertEquals(
                "`$key` uses different placeholders in the two languages",
                resource.values.map(::placeholders),
                other.values.map(::placeholders),
            )
        }
    }

    @Test
    fun `every placeholder the sentence uses is one a caller could fill`() {
        // Positional and low-numbered: `%s` with three arguments is legal Kotlin
        // and illegal Android, and a gap — `%2$s` with no `%1$s` — is a sentence
        // written for arguments its caller does not have.
        resources("values").forEach { (key, resource) ->
            resource.values.forEach { value ->
                val positions = placeholders(value)
                if (positions.isEmpty()) return@forEach
                assertEquals("`$key` skips a placeholder position", (1..positions.size).toList(), positions)
            }
        }
    }

    @Test
    fun `the escapes a resource cannot carry are absent`() {
        listOf("values", "values-en").forEach { folder ->
            resources(folder).forEach { (key, resource) ->
                resource.values.forEach { value ->
                    assertTrue("`$key` in $folder contains a raw double quote, which XML would need escaped", !value.contains('"'))
                    assertTrue("`$key` in $folder carries a newline — that is two sentences", !value.contains('\n'))
                }
            }
        }
    }

    /** The resources of one folder, by name. */
    private fun resources(folder: String): Map<String, Resource> {
        val file = locate(folder)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val children = document.documentElement.childNodes
        val found = LinkedHashMap<String, Resource>()
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            val name = element.getAttribute("name")
            if (name.isEmpty()) continue
            val values = if (element.tagName == "plurals") {
                val items = element.getElementsByTagName("item")
                (0 until items.length).map { (items.item(it) as Element).textContent }
            } else {
                listOf(element.textContent)
            }
            found[name] = Resource(element.tagName, values)
        }
        return found
    }

    /** The strings.xml of one resource folder, with a failure that names the paths tried. */
    private fun locate(folder: String): File {
        val candidates = listOf("src/main/res/$folder/strings.xml", "android/app/src/main/res/$folder/strings.xml")
        candidates.forEach { path ->
            val file = File(path)
            if (file.isFile) return file
        }
        throw AssertionError(
            "no $folder/strings.xml: looked for ${candidates.joinToString(" or ")} from ${File(".").absolutePath}",
        )
    }

    /**
     * The placeholder positions a sentence uses, in the order it names them.
     *
     * A bare `%s` counts as the first one, which is what it is at runtime and what
     * makes it legal to mix with positional forms — a mix this app does not use,
     * but a translator might.
     */
    private fun placeholders(value: String): List<Int> {
        val found = mutableListOf<Int>()
        val pattern = Regex("""%(?:(\d+)\$)?[sdfx]""")
        pattern.findAll(value).forEach { match ->
            found += match.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: (found.size + 1)
        }
        return found.sorted()
    }
}
