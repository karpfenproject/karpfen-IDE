package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [ReferenceIds], the recognizer of reference ids behind the documentation style rules: it finds every kind of
 * id it lists and leaves ordinary prose alone, including words and codes that resemble an id.
 */
class ReferenceIdsTest {

    /** The texts of the reference ids in [text]. */
    private fun idsIn(text: String): List<String> = ReferenceIds.findIn(text).map { it.text }

    @Test
    fun `finds section signs, decisions, work packages, ADRs, decision keys and spec sections`() {
        val text = "See §3.4, § 7, decision 53, Decisions #12, WP-PARSE, ADR 0003, ADR-7, (D2), D8: " +
            "spec section 3.1 and Specification Section 4."
        assertEquals(
            listOf(
                "§3.4", "§ 7", "decision 53", "Decisions #12", "WP-PARSE", "ADR 0003", "ADR-7", "D2", "D8",
                "spec section 3.1", "Specification Section 4",
            ),
            idsIn(text),
        )
    }

    @Test
    fun `ordinary prose is no reference id`() {
        listOf(
            "the decision that text files are the source of truth",
            "the parse budget of the phase-1 specification",
            "a section of the specification, read 3 times",
            "the color 0xFFD1E0, the D10 grid, the key Ctrl+D and 2D1 coordinates",
            "D9 and D0 are no decision keys",
            "a WP- prefix alone, ADR without a number, the § sign alone",
        ).forEach { prose -> assertEquals(emptyList(), idsIn(prose), prose) }
    }

    @Test
    fun `ids are ordered by their offset in the text`() {
        assertEquals(
            listOf(ReferenceId("D1", 0), ReferenceId("WP-LANG", 14), ReferenceId("§2", 28)),
            ReferenceIds.findIn("D1 first, see WP-LANG, then §2"),
        )
    }
}
