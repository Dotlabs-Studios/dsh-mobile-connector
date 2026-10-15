package dev.dsh.mobile.data

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's base-language string resources, read as XML by the tests that have to
 * check *what a sentence says*.
 *
 * The words a reader sees are no longer reachable from Kotlin: they are rows in
 * the tables under `tools/l10n` and `<string>` elements in
 * `src/main/res/values/strings.xml`.
 * That is the point of the migration — the data layer decides *which* sentence,
 * the UI writes it — but a few promises were always made of words and used to be
 * pinned by tests on this side: that removing a project never reads like deleting
 * its files, and that the five chat states are five different things said out
 * loud. Reading the resource file keeps those promises checkable on the JVM: no
 * phone, no Robolectric, no aapt in the loop.
 *
 * The shape of it is one key in, one sentence out: ask for `workspace_remove_confirm`
 * and get `Rimuovi`. The document is parsed once, on the first question asked of
 * it, and the escapes a resource carries are undone on the way out, so a caller
 * compares against the sentence a reader would see rather than against its XML
 * spelling. `&amp;` and `&lt;` are the parser's business and come back as `&` and
 * `<` on their own.
 */
object StringsXml {

    /**
     * Where the base-language resources live, relative to the working directory the
     * JVM tests run in.
     *
     * Gradle runs a module's unit tests from the module directory, which is the
     * first path; the second is a convenience for a runner started from the
     * repository root. Two candidates and not a search: a working directory neither
     * of them fits has to fail loudly, because a helper that quietly reads nothing
     * turns every promise it checks into a test that passes for the wrong reason.
     */
    private val CANDIDATES = listOf(
        "src/main/res/values/strings.xml",
        "android/app/src/main/res/values/strings.xml",
    )

    private val document: Document by lazy { parse() }

    /**
     * Whether a resource named [key] exists, `<string>` or `<plurals>`.
     *
     * @param key the resource name.
     */
    fun has(key: String): Boolean = resource(key) != null

    /**
     * The text of `<string name="key">`.
     *
     * @param key the resource name.
     * @return the sentence as a reader sees it, with the escapes undone.
     */
    fun value(key: String): String {
        val element = located(key)
        if (element.tagName != "string") fail("`$key` is a <${element.tagName}>, not a <string>")
        return text(element)
    }

    /**
     * One form of `<plurals name="key">`.
     *
     * @param key the resource name.
     * @param quantity `one`, `other`, or whichever forms the language has.
     * @return that form's text, with the escapes undone.
     */
    fun plural(key: String, quantity: String): String {
        val element = located(key)
        if (element.tagName != "plurals") fail("`$key` is a <${element.tagName}>, not a <plurals>")
        val items = element.getElementsByTagName("item")
        val wanted = (0 until items.length)
            .map { items.item(it) as Element }
            .firstOrNull { it.getAttribute("quantity") == quantity }
        return text(wanted ?: fail("`$key` has no `$quantity` form in ${source().path}"))
    }

    /**
     * Every resource name in the file, in document order.
     *
     * Used by the test that holds the two languages side by side: the question it
     * asks — does every sentence exist in both? — is a question about the *set* of
     * names, which is exactly what this returns.
     */
    fun keys(): List<String> {
        val children = document.documentElement.childNodes
        return (0 until children.length).mapNotNull { index ->
            (children.item(index) as? Element)?.getAttribute("name")?.takeIf { it.isNotEmpty() }
        }
    }

    /** The element named [key], or a failure that says which file was read. */
    private fun located(key: String): Element =
        resource(key) ?: fail("no resource named `$key` in ${source().path}")

    /** The resource named [key], `<string>` or `<plurals>`, or null when there is none. */
    private fun resource(key: String): Element? {
        val children = document.documentElement.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.getAttribute("name") == key) return child
        }
        return null
    }

    /** The file the resources were read from, failing the test if there is none. */
    private fun source(): File {
        CANDIDATES.forEach { path ->
            val file = File(path)
            if (file.isFile) return file
        }
        fail(
            "no strings.xml: looked for ${CANDIDATES.joinToString(" or ")} " +
                "from ${File(".").absolutePath} — the JVM tests are expected to run " +
                "from the module directory (android/app)",
        )
    }

    /** Parse the resource file once, with a message that says which file failed. */
    private fun parse(): Document {
        val file = source()
        return try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        } catch (problem: Exception) {
            fail("${file.path} could not be read as XML", problem)
        }
    }

    /** The text of [element] with the escapes a resource carries undone. */
    private fun text(element: Element): String = unescape(element.textContent)

    /**
     * Undo the escapes a resource file carries.
     *
     * The generator writes a backslash before every apostrophe in a table, and aapt
     * reads that back as one; the same idea covers the whitespace escapes. Every
     * other backslash is dropped, which is what aapt does with it too.
     */
    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            if (character != '\\' || index == raw.lastIndex) {
                out.append(character)
                index += 1
                continue
            }
            out.append(
                when (val escaped = raw[index + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    else -> escaped
                },
            )
            index += 2
        }
        return out.toString()
    }

    /** Fail the test with [what], since a missing resource is never a pass. */
    private fun fail(what: String, cause: Throwable? = null): Nothing =
        throw AssertionError(what, cause)
}
