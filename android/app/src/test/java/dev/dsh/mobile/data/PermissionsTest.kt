package dev.dsh.mobile.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the permission presets.
 *
 * A preset is a name over two knobs — what the agent may touch, and whether it
 * has to ask first — so the interesting cases are the ones where the name and
 * the behaviour disagree. Judging a preset by its name would be exactly the
 * mistake that makes a security control decorative.
 *
 * What a preset is *called* is no longer decided here: the app's own words live
 * in the string resources and are resolved by the composable that draws them, so
 * they are checked against the translation table rather than against a Kotlin
 * literal. What is decided here — and what these tests pin down — is which
 * preset a value is, which fallback a preset with no words of its own gets, and
 * whether it is dangerous.
 */
class PermissionsTest {

    /**
     * Build one preset.
     *
     * @param value the name the harness resolves.
     * @param approval the approval policy it bundles.
     * @param name the host's label.
     * @param description the host's explanation.
     */
    private fun preset(
        value: String,
        approval: String? = "ask",
        name: String = value,
        description: String? = null,
    ) = PermissionOption(value = value, name = name, description = description, approval = approval)

    @Test
    fun `the risk is read from the approval knob and not from the name`() {
        // A deployment is free to invent presets, so "does it stop asking?" has
        // to be answered by what the preset does.
        assertTrue(preset("danger-full-access", approval = "never").grantsWithoutAsking)
        assertFalse(preset("full-speed", approval = "ask").grantsWithoutAsking)
        assertTrue("an invented name that never asks is still a grant", preset("yolo", approval = "never").grantsWithoutAsking)
        assertFalse("the shipped name with a stricter policy is not", preset("danger-full-access", approval = "ask").grantsWithoutAsking)
    }

    @Test
    fun `an unknown approval policy is not treated as a grant`() {
        assertFalse(preset("x", approval = null).grantsWithoutAsking)
        assertFalse(preset("x", approval = "sometimes").grantsWithoutAsking)
    }

    @Test
    fun `the presets this app words are found by the value the harness resolves`() {
        // These are the four the app answers in its own words; the test names the
        // mapping rather than the wording, because the wording is a resource.
        assertEquals(PermissionPreset.ReadOnly, PermissionPreset.of("read-only"))
        assertEquals(PermissionPreset.WorkspaceWrite, PermissionPreset.of("workspace-write"))
        assertEquals(PermissionPreset.FullAccess, PermissionPreset.of("danger-full-access"))
        assertEquals(PermissionPreset.Auto, PermissionPreset.of("auto"))
    }

    @Test
    fun `a preset this app cannot word is other rather than a near guess`() {
        // The value is a protocol identifier, so it is matched exactly: a
        // deployment's own preset, a value with the wrong case and an absent
        // value are all presets this app has no sentence for — and a guessed
        // sentence would describe a permission nobody granted.
        assertEquals(PermissionPreset.Other, PermissionPreset.of("ask-me"))
        assertEquals(PermissionPreset.Other, PermissionPreset.of("read_only"))
        assertEquals(PermissionPreset.Other, PermissionPreset.of("READ-ONLY"))
        assertEquals(PermissionPreset.Other, PermissionPreset.of(null))
    }

    @Test
    fun `an option reports the preset it is`() {
        assertEquals(PermissionPreset.FullAccess, preset("danger-full-access").preset)
        assertEquals(PermissionPreset.Other, preset("yolo", name = "Yolo").preset)
    }

    @Test
    fun `an invented preset keeps the host's own label`() {
        // The deployment that invented it knows better than this app what to
        // call it, so the host's label is what the row and the chip fall back to.
        assertEquals("Ask Me First", preset("ask-me", name = "Ask Me First").hostLabel)
        assertEquals(PermissionPreset.Other, preset("ask-me", name = "Ask Me First").preset)
    }

    @Test
    fun `a preset with no label at all falls back to its value`() {
        assertEquals("mystery", PermissionOption(value = "mystery").hostLabel)
    }

    @Test
    fun `an invented preset is explained by the host or not at all`() {
        // Inventing a sentence about knobs we cannot see would be a promise the
        // app is not in a position to make: the host's description is all there
        // is, and a host that wrote none leaves the explanation absent — which is
        // what the row draws as nothing.
        val described = preset("weird", description = "Does something specific.")

        assertEquals("Does something specific.", described.description)
        assertNull(preset("weird").description)
    }

    @Test
    fun `the words for the presets the app names are in the base language`() {
        // The app's own sentences for the presets are resources now, and this is
        // the promise that survived the move: every key the picker asks for
        // carries a sentence, in the language the app falls back to.
        listOf(
            "perm_preset_read_only",
            "perm_preset_workspace_write",
            "perm_preset_full_access",
            "perm_preset_auto",
            "perm_detail_read_only",
            "perm_detail_workspace_write",
            "perm_detail_full_access",
            "perm_detail_auto",
            "perm_chip_workspace_write",
            "perm_chip_none",
        ).forEach { key ->
            assertTrue("`$key` is missing from strings.xml", StringsXml.has(key))
            assertTrue("`$key` carries no sentence", StringsXml.value(key).isNotBlank())
        }
    }

    @Test
    fun `the dangerous preset says in words that the agent stops asking`() {
        // Read out of the resource rather than out of Kotlin — that is where the
        // sentence lives now — but it is still the one line worth pinning: a
        // reader grants full access on the strength of it.
        assertTrue(
            "workspace-write is not described as asking first",
            StringsXml.value("perm_detail_workspace_write").contains("chiede il permesso"),
        )
        assertTrue(
            "full access is not described as never asking",
            StringsXml.value("perm_detail_full_access").contains("nessuna richiesta di permesso"),
        )
        assertTrue(
            "the warning badge does not say there is no request",
            StringsXml.value("perm_no_approval").contains("richiesta di permesso"),
        )
    }

    @Test
    fun `an option is found by the value the host resolves`() {
        val catalog = PermissionCatalog(
            current = "read-only",
            options = listOf(preset("read-only", name = "Read Only"), preset("workspace-write", name = "Workspace Write")),
        )

        assertEquals("Read Only", catalog.optionFor("read-only")!!.name)
        assertNull(catalog.optionFor("nonexistent"))
        assertNull("a null current is not an option named null", catalog.optionFor(null))
    }

    @Test
    fun `a session the host could not report has no preset rather than a default one`() {
        // Substituting the deployment default here would label a chat with a
        // permission it may not have, which is the one thing this must not do.
        val catalog = PermissionCatalog(sessionId = "s", current = null, defaultPreset = "workspace-write", options = listOf(preset("workspace-write")))

        assertNull(catalog.optionFor(catalog.current))
    }

    @Test
    fun `the wire payload decodes with the knobs the route fills in`() {
        val json = """
            {"sessionId":"s","current":"workspace-write","defaultPreset":"workspace-write",
             "options":[{"value":"read-only","name":"Read Only","description":"Read.",
                         "sandbox":"read-only","approval":"ask"},
                        {"value":"auto","name":"Auto","description":null,"sandbox":null,"approval":null}]}
        """.trimIndent()

        val catalog = Json { ignoreUnknownKeys = true }.decodeFromString<PermissionCatalog>(json)

        assertEquals("workspace-write", catalog.current)
        assertEquals(2, catalog.options.size)
        val auto = catalog.optionFor("auto")!!
        assertEquals("the app words Auto, the host need not explain it", PermissionPreset.Auto, auto.preset)
        assertNull("an unresolved preset reports unknown knobs, not permissive ones", auto.sandbox)
        assertNull(auto.approval)
        assertNull("and an absent description stays absent rather than becoming empty", auto.description)
        assertFalse(auto.grantsWithoutAsking)
        assertFalse(auto.knobsKnown)
    }

    @Test
    fun `an unknown field from a newer host does not break the read`() {
        val json = """{"current":"read-only","options":[{"value":"read-only","name":"Read Only","future":true}]}"""

        val catalog = Json { ignoreUnknownKeys = true }.decodeFromString<PermissionCatalog>(json)

        assertEquals(1, catalog.options.size)
        assertNull(catalog.sessionId)
        assertNull(catalog.defaultPreset)
    }
}
