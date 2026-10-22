package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [ReferenceIds], the recognizer of reference ids behind the documentation style rules: it finds every kind of
 * id it lists, in the phrasings the planning documents and the reviews of the project use, and leaves ordinary prose
 * alone, including words and codes that resemble an id.
 */
class ReferenceIdsTest {

    /** The texts of the reference ids in [text]. */
    private fun idsIn(text: String): List<String> = ReferenceIds.findIn(text).map { it.text }

    @Test
    fun `finds section signs, decisions, work packages, ADRs and spec sections`() {
        val text = "See §3.4, § 7, decision 53, Decisions #12, WP-PARSE, ADR 0003, ADR-7, " +
            "spec section 3.1 and Specification Section 4."
        assertEquals(
            listOf("§3.4", "§ 7", "decision 53", "Decisions #12", "WP-PARSE", "ADR 0003", "ADR-7", "spec section 3.1", "Specification Section 4"),
            idsIn(text),
        )
    }

    @Test
    fun `finds sections and work packages named in words`() {
        assertEquals(listOf("section 4.2"), idsIn("As section 4.2 of the phase-1 specification requires"))
        assertEquals(listOf("sections 2"), idsIn("as sections 2 and 3 describe"))
        assertEquals(listOf("work package PARSE", "Work-package 7"), idsIn("See the work package PARSE and Work-package 7."))
    }

    @Test
    fun `finds review rounds and the keys of review findings`() {
        assertEquals(
            listOf("review round 2, \"hidden-completion-close-ignores-reveal\""),
            idsIn("(review round 2, \"hidden-completion-close-ignores-reveal\")"),
        )
        assertEquals(listOf("review finding \"hidden-completion-captures-keys\""), idsIn("the review finding \"hidden-completion-captures-keys\""))
        assertEquals(listOf("review finding find-regex-on-ui-thread"), idsIn("fixes the review finding find-regex-on-ui-thread"))
        assertEquals(listOf("review: `shift-tab-moves-focus-out`"), idsIn("(review: `shift-tab-moves-focus-out`)"))
        assertEquals(listOf("Review round 3"), idsIn("Review round 3 found nothing."))
    }

    @Test
    fun `finds short keys where the text uses them as references`() {
        assertEquals(listOf("phase P2", "finding E7"), idsIn("See phase P2 and finding E7."))
        assertEquals(listOf("key D9 and D12"), idsIn("the decision key D9 and D12"))
        assertEquals(listOf("Decisions D1, D2 or D3"), idsIn("Decisions D1, D2 or D3 hold."))
        assertEquals(listOf("(D2)", "(P1, P2)", "D8"), idsIn("as decided (D2), done in (P1, P2); D8: the editor"))
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
            "D3.js and the D2 diagram language",
            "the control points P1 and P2 of a curve, 1E7 iterations, F3 and (F3) find the next match",
            "the work package that adds find, the work-package plan",
            "the diagnostics `descriptor-missing-file` and \"context-no-metamodel\" are findings",
            "after the review, follow-up work; the reviewed code",
        ).forEach { prose -> assertEquals(emptyList(), idsIn(prose), prose) }
    }

    @Test
    fun `ids are ordered by their offset in the text`() {
        assertEquals(
            listOf(ReferenceId("(D1)", 0), ReferenceId("WP-LANG", 16), ReferenceId("§2", 30)),
            ReferenceIds.findIn("(D1) first, see WP-LANG, then §2"),
        )
    }
}
