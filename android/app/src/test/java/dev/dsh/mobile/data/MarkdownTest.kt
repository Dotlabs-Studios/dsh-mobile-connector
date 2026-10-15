package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the markdown parsing behind a formatted reply.
 *
 * Two kinds of case matter. The first is the construct itself — a heading that
 * becomes a heading, a table that becomes a table. The second, and the one that
 * decides whether the parser is safe to ship, is the *near miss*: the line that
 * looks like a construct and is not one. A paragraph containing a pipe is not a
 * table, `snake_case` is not emphasis, and `#Heading` is not a heading — and
 * each of those, misread, corrupts text the author wrote deliberately.
 */
class MarkdownTest {

    /**
     * The single block a body parses to.
     *
     * @param text the body.
     * @return its only block.
     */
    private fun only(text: String): MdBlock {
        val blocks = parseMarkdown(text)
        assertEquals("expected exactly one block, got $blocks", 1, blocks.size)
        return blocks.single()
    }

    /**
     * The text of a run of inline content, with every marker removed.
     *
     * @param runs the parsed runs.
     * @return the concatenated text.
     */
    private fun flat(runs: List<MdInline>): String = runs.joinToString("") { run ->
        when (run) {
            is MdInline.Text -> run.text
            is MdInline.Code -> run.text
            is MdInline.Strong -> flat(run.children)
            is MdInline.Emphasis -> flat(run.children)
            is MdInline.Strike -> flat(run.children)
            is MdInline.Link -> run.label
        }
    }

    @Test
    fun `plain prose is one paragraph`() {
        assertEquals(MdBlock.Paragraph("ciao, come stai?"), only("ciao, come stai?"))
    }

    @Test
    fun `a bare https address is a link labelled by itself`() {
        val runs = parseInline("vedi https://example.com/docs per dettagli")
        val link = runs.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com/docs", link.target)
        assertEquals("https://example.com/docs", link.label)
        // The prose survives on both sides of it, spaces included.
        assertEquals("vedi  per dettagli", flat(runs.filterNot { it is MdInline.Link }))
    }

    @Test
    fun `trailing punctuation belongs to the sentence, not the address`() {
        val runs = parseInline("la pagina è https://example.com/it.")
        assertEquals("https://example.com/it", runs.filterIsInstance<MdInline.Link>().single().target)
        assertEquals("la pagina è .", flat(runs.filterNot { it is MdInline.Link }))
    }

    @Test
    fun `a parenthetical keeps its closing bracket`() {
        val runs = parseInline("(vedi https://example.com/a) poi")
        assertEquals("https://example.com/a", runs.filterIsInstance<MdInline.Link>().single().target)
        // The bracket the sentence opened is still in the prose, unswallowed.
        val flat = flat(runs.filterNot { it is MdInline.Link })
        assertTrue(flat.startsWith("(vedi ") && flat.contains(")"))
    }

    @Test
    fun `a bracketed path keeps the brackets it opened`() {
        val runs = parseInline("https://example.com/wiki/Python_(linguaggio)")
        assertEquals("https://example.com/wiki/Python_(linguaggio)", runs.filterIsInstance<MdInline.Link>().single().target)
    }

    @Test
    fun `an address without a host is prose`() {
        // `https://` followed by a space is not a link, and inventing one would
        // make a tap send a request the author never wrote.
        assertTrue(parseInline("premendo https:// si apre il sito").none { it is MdInline.Link })
        assertTrue(parseInline("il file è in http://localhost forse").filterIsInstance<MdInline.Link>().isEmpty())
    }

    @Test
    fun `a markdown link is not re-parsed as a bare address`() {
        val runs = parseInline("[la documentazione](https://example.com/docs).")
        val link = runs.filterIsInstance<MdInline.Link>().single()
        assertEquals("la documentazione", link.label)
        assertEquals("https://example.com/docs", link.target)
    }

    @Test
    fun `hard-wrapped lines join into one paragraph`() {
        // Markdown's soft break: the author's line wrapping is not a hard break,
        // and treating it as one puts a gap in the middle of a sentence.
        assertEquals(MdBlock.Paragraph("una frase lunga che continua"), only("una frase lunga\nche continua"))
    }

    @Test
    fun `a blank line starts a new paragraph`() {
        val blocks = parseMarkdown("primo\n\nsecondo")

        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph("primo"), blocks[0])
        assertEquals(MdBlock.Paragraph("secondo"), blocks[1])
    }

    @Test
    fun `headings keep their level`() {
        assertEquals(MdBlock.Heading(1, "Titolo"), only("# Titolo"))
        assertEquals(MdBlock.Heading(3, "Sezione"), only("### Sezione"))
    }

    @Test
    fun `a closing run of hashes is not part of the title`() {
        assertEquals(MdBlock.Heading(2, "Titolo"), only("## Titolo ##"))
    }

    @Test
    fun `a hash without a space is not a heading`() {
        // `#1`, `#tag` and `#!/bin/sh` are all things an agent writes literally.
        assertEquals(MdBlock.Paragraph("#Heading"), only("#Heading"))
        assertEquals(MdBlock.Paragraph("#hashtag"), only("#hashtag"))
        assertEquals(MdBlock.Paragraph("#!/bin/sh"), only("#!/bin/sh"))
    }

    @Test
    fun `more than six hashes is not a heading`() {
        assertEquals(MdBlock.Paragraph("####### troppo"), only("####### troppo"))
    }

    @Test
    fun `a fenced block carries its language`() {
        val code = only("```pwsh\nls -la\n```") as MdBlock.CodeBlock

        assertEquals("pwsh", code.language)
        assertEquals("ls -la", code.code)
    }

    @Test
    fun `an unclosed fence is code, not prose`() {
        // The state every streaming reply passes through: treating it as prose
        // would reflow the message on every poll.
        val blocks = parseMarkdown("ecco:\n```kt\nval x = 1")

        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph("ecco:"), blocks[0])
        assertEquals("kt", (blocks[1] as MdBlock.CodeBlock).language)
        assertEquals("val x = 1", (blocks[1] as MdBlock.CodeBlock).code)
    }

    @Test
    fun `an explicit title wins over the language`() {
        val code = only("```ts title=\"main.kt\"\nval x = 1\n```") as MdBlock.CodeBlock

        assertEquals("ts", code.language)
        assertEquals("main.kt", code.title)
    }

    @Test
    fun `a longer closing fence still closes`() {
        val code = only("```\ncorpo\n````") as MdBlock.CodeBlock

        assertEquals("corpo", code.code)
    }

    @Test
    fun `an indented backtick run is prose`() {
        // Four spaces of indent is markdown's indented code block, and treating
        // it as a fence would swallow text the author wrote as prose.
        assertTrue(only("    ```\nnon un fence") is MdBlock.Paragraph)
    }

    @Test
    fun `an empty fence produces no block`() {
        val blocks = parseMarkdown("prima\n```\n```\ndopo")

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is MdBlock.Paragraph })
    }

    @Test
    fun `a bullet list collects its entries`() {
        val list = only("- uno\n- due\n* tre") as MdBlock.Bullets

        assertEquals(3, list.items.size)
        assertEquals(listOf("uno", "due", "tre"), list.items.map { it.text })
        assertTrue("a bullet has no number", list.items.all { it.number == null })
    }

    @Test
    fun `an ordered list keeps the author's numbers`() {
        // A list that starts at 5, or repeats a number, means something;
        // renumbering it would silently rewrite the author's point.
        val list = only("5. cinque\n6. sei\n9. nove") as MdBlock.Bullets

        assertEquals(listOf(5, 6, 9), list.items.map { it.number })
    }

    @Test
    fun `indentation nests an entry`() {
        val list = only("- fuori\n  - dentro\n    - più dentro") as MdBlock.Bullets

        assertEquals(listOf(0, 1, 2), list.items.map { it.depth })
    }

    @Test
    fun `an indented list marker is not a list`() {
        assertTrue(only("    - non è una lista") is MdBlock.Paragraph)
    }

    @Test
    fun `a list interrupts a paragraph`() {
        // How a reply usually opens one: a sentence, a colon, then the steps.
        val blocks = parseMarkdown("Ecco cosa ho fatto:\n- primo\n- secondo")

        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph("Ecco cosa ho fatto:"), blocks[0])
        assertEquals(2, (blocks[1] as MdBlock.Bullets).items.size)
    }

    @Test
    fun `a quote keeps its lines together`() {
        val quote = only("> prima riga\n> seconda riga") as MdBlock.Quote

        assertEquals("prima riga\nseconda riga", quote.text)
    }

    @Test
    fun `thematic breaks are recognised`() {
        assertTrue(only("---") is MdBlock.Rule)
        assertTrue(only("***") is MdBlock.Rule)
        assertTrue(only("_ _ _") is MdBlock.Rule)
    }

    @Test
    fun `two dashes are not a rule`() {
        assertEquals(MdBlock.Paragraph("--"), only("--"))
    }

    @Test
    fun `a table becomes a table`() {
        val table = only("| Modello | Token |\n|---|---|\n| mimo | 200k |\n| glm | 128k |") as MdBlock.Table

        assertEquals(listOf("Modello", "Token"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("mimo", "200k"), table.rows[0])
        assertEquals(listOf("glm", "128k"), table.rows[1])
    }

    @Test
    fun `a short row is padded to the header's width`() {
        // Otherwise every renderer call site has to know that an author may have
        // left a trailing cell empty.
        val table = only("| a | b | c |\n|---|---|---|\n| 1 |") as MdBlock.Table

        assertEquals(listOf("1", "", ""), table.rows.single())
    }

    @Test
    fun `a paragraph with a pipe is not a table`() {
        // A shell pipeline and a Windows path both contain pipes; both are prose.
        assertEquals(MdBlock.Paragraph("grep -v x | sort"), only("grep -v x | sort"))
    }

    @Test
    fun `a pipe line without a delimiter row is not a table`() {
        assertEquals(MdBlock.Paragraph("| a | b |"), only("| a | b |"))
    }

    @Test
    fun `a delimiter needs three dashes`() {
        // Two dashes is a near miss the renderer must not act on, whatever it
        // decides to call the line.
        val blocks = parseMarkdown("| a |\n|--|")

        assertTrue("a two-dash row is not a table", blocks.none { it is MdBlock.Table })
    }

    @Test
    fun `an empty body yields nothing`() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("   \n\n  ").isEmpty())
    }

    @Test
    fun `inline code is one run and shields its content`() {
        val runs = parseInline("usa `val x` qui")

        assertEquals(3, runs.size)
        assertEquals("usa ", (runs[0] as MdInline.Text).text)
        assertEquals("val x", (runs[1] as MdInline.Code).text)
        assertEquals(" qui", (runs[2] as MdInline.Text).text)
        assertEquals("**non grassetto**", flat(parseInline("`**non grassetto**`")))
    }

    @Test
    fun `strong and emphasis are distinguished`() {
        assertTrue(parseInline("**grassetto**").single() is MdInline.Strong)
        assertTrue(parseInline("__grassetto__").single() is MdInline.Strong)
        assertTrue(parseInline("*corsivo*").single() is MdInline.Emphasis)
        assertTrue(parseInline("_corsivo_").single() is MdInline.Emphasis)
    }

    @Test
    fun `an underscore inside an identifier is literal`() {
        // `snake_case_name` is a name, and this is the case the naive rule gets
        // reliably wrong.
        val runs = parseInline("usa snake_case_name qui")

        assertEquals("usa snake_case_name qui", flat(runs))
        assertTrue(runs.all { it is MdInline.Text })
    }

    @Test
    fun `strikethrough is recognised`() {
        assertTrue(parseInline("~~vecchio~~").single() is MdInline.Strike)
    }

    @Test
    fun `links keep their label and target`() {
        val link = parseInline("vedi [la guida](https://example.com/x)").filterIsInstance<MdInline.Link>().single()

        assertEquals("la guida", link.label)
        assertEquals("https://example.com/x", link.target)
    }

    @Test
    fun `an image becomes its alt text`() {
        // A path the phone cannot fetch is not an image; the alt text is what the
        // author told the reader to see.
        val runs = parseInline("![uno screenshot](schermo.png)")

        assertEquals("uno screenshot", flat(runs))
        assertTrue(runs.none { it is MdInline.Link })
    }

    @Test
    fun `a marker with no partner stays literal`() {
        assertEquals("2 * 3 = 6", flat(parseInline("2 * 3 = 6")))
        assertEquals("**aperto", flat(parseInline("**aperto")))
        assertEquals("~circa", flat(parseInline("~circa")))
    }

    @Test
    fun `markers nest`() {
        val runs = parseInline("**grassetto con *corsivo* dentro**")

        val strong = runs.single() as MdInline.Strong
        assertTrue(strong.children.any { it is MdInline.Emphasis })
        assertEquals("grassetto con corsivo dentro", flat(runs))
    }

    @Test
    fun `bold applies to the marked span and nothing else`() {
        // Asserting the run *boundaries* is the point. A parser that swallows two
        // characters before every marker still produces a Strong run and still
        // passes a test that only asks whether the total text is plausible — it
        // just puts the wrong words inside it.
        val runs = parseInline("prima **dentro** dopo")

        assertEquals(3, runs.size)
        assertEquals("prima ", (runs[0] as MdInline.Text).text)
        assertEquals("dentro", flat((runs[1] as MdInline.Strong).children))
        assertEquals(" dopo", (runs[2] as MdInline.Text).text)
    }

    @Test
    fun `a marker later in the text does not reach back to the start`() {
        // The regression that shipped: parsing began at the first character of the
        // paragraph, searched forward for a closing `**`, found the one belonging
        // to a bold span much later, and made everything up to it bold — dropping
        // the two characters it started on. The reader saw "Gli asset" as
        // "i asset", and the culprit was one missing check that the marker is
        // actually where the scan is standing.
        val runs = parseInline("Gli asset erano già in `src/` e quindi disegnati a **×2** — i quattro nuovi.")

        assertEquals("Gli asset erano già in src/ e quindi disegnati a ×2 — i quattro nuovi.", flat(runs))
        val bold = runs.filterIsInstance<MdInline.Strong>().single()
        assertEquals("×2", flat(bold.children))
        assertTrue("nothing else is bold", runs.first() is MdInline.Text)
        assertEquals("Gli asset erano già in ", (runs.first() as MdInline.Text).text)
    }

    @Test
    fun `arithmetic is not emphasis`() {
        // `*` between spaces is a multiplication sign, and CommonMark agrees: a
        // marker that opens after whitespace cannot open emphasis at all.
        val runs = parseInline("2 * 3 = 6 e 4 * 5 = 20")

        assertEquals("2 * 3 = 6 e 4 * 5 = 20", flat(runs))
        assertTrue(runs.all { it is MdInline.Text })
    }

    @Test
    fun `a padded marker pair is not emphasis`() {
        assertEquals("** non grassetto **", flat(parseInline("** non grassetto **")))
    }

    @Test
    fun `text around a bold span in the middle is preserved`() {
        val runs = parseInline("quindi restavano sui **corpi procedurali a primitive**.")

        assertEquals(3, runs.size)
        assertEquals("quindi restavano sui ", (runs[0] as MdInline.Text).text)
        assertEquals("corpi procedurali a primitive", flat((runs[1] as MdInline.Strong).children))
        assertEquals(".", (runs[2] as MdInline.Text).text)
    }

    @Test
    fun `every sample keeps all of its words`() {
        // The invariant that matters most, checked against the shapes that
        // actually appear in an agent's answers: whatever the markers are, the
        // words are all still there. A parser can pass every structural assertion
        // and still corrupt every sentence it renders.
        val samples = listOf(
            "A differenza dei primi tre — convertiti a `rig.height` 1,000 e disegnati a **×2** — i quattro nuovi hanno `rig.height` pari alla **loro statura reale**." to
                "A differenza dei primi tre — convertiti a rig.height 1,000 e disegnati a ×2 — i quattro nuovi hanno rig.height pari alla loro statura reale.",
            "Gli asset erano già in `src/assets/` come `*.mesh.js`, quindi restavano sui **corpi procedurali a primitive**." to
                "Gli asset erano già in src/assets/ come *.mesh.js, quindi restavano sui corpi procedurali a primitive.",
            "Testo con **grassetto**, *corsivo*, `codice` e ~~barrato~~ in mezzo." to
                "Testo con grassetto, corsivo, codice e barrato in mezzo.",
            "**Inizia in grassetto** e poi continua normale." to
                "Inizia in grassetto e poi continua normale.",
            "Finisce in **grassetto**." to "Finisce in grassetto.",
            "Nessun marcatore qui, solo testo normale." to "Nessun marcatore qui, solo testo normale.",
            "2 * 3 = 6 e 4 * 5 = 20" to "2 * 3 = 6 e 4 * 5 = 20",
        )

        samples.forEach { (source, expected) ->
            assertEquals("testo perso o aggiunto in: $source", expected, flat(parseInline(source)))
        }
    }

    @Test
    fun `a double backtick span may contain one`() {
        assertEquals("a ` b", (parseInline("``a ` b``").single() as MdInline.Code).text)
    }

    @Test
    fun `a lone backtick stays literal`() {
        assertEquals("un ` solo", flat(parseInline("un ` solo")))
    }

    @Test
    fun `a full reply parses into its parts`() {
        // The shape an actual answer takes, and the reason any of this exists: a
        // heading, prose with emphasis, a list, and a code block.
        val body = """
            ## Risultato

            Ho corretto **due cose** e lanciato `gradlew`:

            - il wrapper mancava
            - il diff era sbagliato

            ```pwsh
            .\gradlew.bat assembleDebug
            ```
        """.trimIndent()

        val blocks = parseMarkdown(body)

        assertEquals(4, blocks.size)
        assertEquals(MdBlock.Heading(2, "Risultato"), blocks[0])
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertEquals(2, (blocks[2] as MdBlock.Bullets).items.size)
        assertEquals("pwsh", (blocks[3] as MdBlock.CodeBlock).language)
    }
}
