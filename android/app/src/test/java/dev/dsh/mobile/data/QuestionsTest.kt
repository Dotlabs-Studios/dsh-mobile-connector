package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for reading an agent's question out of its tool call.
 *
 * The JSON here is written by a model, not by the harness, so the interesting
 * cases are the malformed ones: a missing id, an option with no label, a field
 * spelled the other way. None of them may throw, and none may produce a card
 * that offers a choice the reader cannot actually make — an option that is not
 * there is better than an option that answers nothing.
 */
class QuestionsTest {

    /**
     * The real shape, copied from a session log.
     *
     * @param extra an extra field to inject into the question object.
     * @return the argument JSON.
     */
    private fun arguments(extra: String = ""): String = """
        {"questions":[{"header":"Tempistica backend","id":"timing"$extra,
          "options":[
            {"label":"Deploya adesso (Consigliato)","description":"Faccio subito il deploy"},
            {"label":"Aspetta","description":"Prima cambio il DNS"}],
          "question":"Come procediamo con il deploy?"}]}
    """.trimIndent()

    @Test
    fun `the full shape is read`() {
        val question = parseQuestions(arguments()).single()

        assertEquals("timing", question.id)
        assertEquals("Tempistica backend", question.header)
        assertEquals("Tempistica backend", question.title)
        assertEquals("Come procediamo con il deploy?", question.question)
        assertEquals(2, question.options.size)
        assertEquals("Deploya adesso (Consigliato)", question.options[0].label)
        assertEquals("Faccio subito il deploy", question.options[0].description)
        assertEquals(false, question.multiSelect)
    }

    @Test
    fun `both spellings of multi-select are understood`() {
        // The tool schema writes one and the harness's own type the other, and
        // both are in real logs.
        assertTrue(parseQuestions(arguments(",\"multi_select\":true")).single().multiSelect)
        assertTrue(parseQuestions(arguments(",\"multiSelect\":true")).single().multiSelect)
        assertEquals(false, parseQuestions(arguments(",\"multi_select\":false")).single().multiSelect)
    }

    @Test
    fun `a question without an id still gets one`() {
        // The answer is keyed by id, so a question with none could not be
        // answered at all; standing in a position is better than dropping a
        // question the reader can see.
        val question = parseQuestions("""{"questions":[{"question":"Quale?","options":[{"label":"A"}]}]}""").single()

        assertEquals("q0", question.id)
    }

    @Test
    fun `a question with no text is dropped`() {
        assertTrue(parseQuestions("""{"questions":[{"id":"a","question":"   "},{"id":"b","question":"Vera?"}]}""").size == 1)
    }

    @Test
    fun `an option with no label is dropped`() {
        val question = parseQuestions(
            """{"questions":[{"id":"a","question":"Quale?","options":[{"description":"niente etichetta"},{"label":"Buona"}]}]}""",
        ).single()

        assertEquals(1, question.options.size)
        assertEquals("Buona", question.options.single().label)
    }

    @Test
    fun `a question with no options is kept`() {
        // The agent may ask for free text, and dropping the card would hide the
        // question entirely.
        val question = parseQuestions("""{"questions":[{"id":"a","question":"Raccontami."}]}""").single()

        assertTrue(question.options.isEmpty())
        assertEquals("Raccontami.", question.question)
    }

    @Test
    fun `an absent header is null rather than an empty label`() {
        val question = parseQuestions("""{"questions":[{"id":"a","question":"Quale?","header":"  "}]}""").single()

        assertNull(question.header)
        assertNull(question.title)
    }

    @Test
    fun `anything unparsable yields no questions`() {
        assertTrue(parseQuestions(null).isEmpty())
        assertTrue(parseQuestions("").isEmpty())
        assertTrue(parseQuestions("non json").isEmpty())
        assertTrue(parseQuestions("[]").isEmpty())
        assertTrue(parseQuestions("""{"questions":"non una lista"}""").isEmpty())
        assertTrue(parseQuestions("""{"question":[{"id":"a"}]}""").isEmpty())
    }

    @Test
    fun `the recommended marker is read and removed`() {
        val recommended = QuestionOption(label = "Deploya adesso: cambio il DNS (Consigliato)", description = null)
        val plain = QuestionOption(label = "Aspetta", description = null)

        assertTrue(recommended.recommended)
        assertEquals("Deploya adesso: cambio il DNS", recommended.cleanLabel)
        assertTrue(!plain.recommended)
        assertEquals("Aspetta", plain.cleanLabel)
    }

    @Test
    fun `the English marker is recognised too`() {
        val option = QuestionOption(label = "Ship it (Recommended)", description = null)

        assertTrue(option.recommended)
        assertEquals("Ship it", option.cleanLabel)
    }

    @Test
    fun `a trailing marker with nothing before it does not eat the label`() {
        val option = QuestionOption(label = "(Consigliato)", description = null)

        assertEquals("", option.cleanLabel)
    }

    @Test
    fun `answers are read back from a tool result`() {
        val answers = parseQuestionAnswers("""{"answers":[{"id":"timing","selected":["Deploya adesso (Consigliato)"]}]}""")

        assertEquals(listOf("Deploya adesso (Consigliato)"), answers["timing"])
    }

    @Test
    fun `a free-text answer counts as an answer`() {
        // Dropping it would make an answered question read as unanswered.
        val answers = parseQuestionAnswers("""{"answers":[{"id":"a","selected":[],"custom":"fai tu"}]}""")

        assertEquals(listOf("fai tu"), answers["a"])
    }

    @Test
    fun `several answers and several choices are kept in order`() {
        val answers = parseQuestionAnswers(
            """{"answers":[{"id":"a","selected":["X","Y"]},{"id":"b","selected":["Z"]}]}""",
        )

        assertEquals(listOf("X", "Y"), answers["a"])
        assertEquals(listOf("Z"), answers["b"])
    }

    @Test
    fun `an unparsable result yields no answers`() {
        assertTrue(parseQuestionAnswers(null).isEmpty())
        assertTrue(parseQuestionAnswers("").isEmpty())
        assertTrue(parseQuestionAnswers("non json").isEmpty())
        assertTrue(parseQuestionAnswers("""{"answers":[{"selected":["X"]}]}""").isEmpty())
    }

    @Test
    fun `an answer is empty only when nothing at all was chosen`() {
        assertTrue(QuestionAnswer(id = "a").isEmpty)
        assertTrue(QuestionAnswer(id = "a", selected = emptyList(), custom = "  ").isEmpty)
        assertTrue(!QuestionAnswer(id = "a", selected = listOf("X")).isEmpty)
        assertTrue(!QuestionAnswer(id = "a", custom = "fai tu").isEmpty)
    }
}
