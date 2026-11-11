/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [ReferenceIds], the recognizer of reference ids behind the documentation style rules. It finds every kind of
 * id it lists, in the phrasings that planning documents and reviews use. It leaves ordinary prose alone, including
 * words and codes that resemble an id.
 */
class ReferenceIdsTest {

    /** The texts of the reference ids in [text]. */
    private fun idsIn(text: String): List<String> = ReferenceIds.findIn(text).map { it.text }

    @Test
    fun `finds section signs, decisions, work packages, ADRs and spec sections`() {
        val text = "See §3.4, § 7, decision 53, Decisions #12, WP-PARSE, ADR 0003, ADR-7, " +
            "spec section 3.1 and Specification Section 4."
        assertEquals(
            listOf(
                "§3.4", "§ 7", "decision 53", "Decisions #12", "WP-PARSE", "ADR 0003", "ADR-7", "spec section 3.1",
                "Specification Section 4",
            ),
            idsIn(text),
        )
    }

    @Test
    fun `finds sections and work packages named in words`() {
        assertEquals(listOf("section 4.2"), idsIn("As section 4.2 of the parser specification requires"))
        assertEquals(listOf("sections 2"), idsIn("as sections 2 and 3 describe"))
        assertEquals(listOf("work package PARSE", "Work-package 7"), idsIn("See the work package PARSE and Work-package 7."))
    }

    @Test
    fun `finds review rounds and the keys of review findings`() {
        assertEquals(
            listOf("review round 2, \"hidden-completion-close-ignores-reveal\""),
            idsIn("(review round 2, \"hidden-completion-close-ignores-reveal\")"),
        )
        assertEquals(
            listOf("review finding \"hidden-completion-captures-keys\""),
            idsIn("the review finding \"hidden-completion-captures-keys\""),
        )
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
    fun `finds numbered rules, possessive items, records, work-package names and keys beside their words`() {
        assertEquals(listOf("rule 7 of the work-package plan"), idsIn("(rule 7 of the work-package plan)"))
        assertEquals(
            listOf("steps 3\u20134 of the text-editing specification"),
            idsIn("as steps 3\u20134 of the text-editing specification say"),
        )
        assertEquals(listOf("proposal's item 6"), idsIn("the proposal's item 6"))
        assertEquals(listOf("INTEGRATION's"), idsIn("(INTEGRATION's e2e)"))
        assertEquals(listOf("INTEGRATION's"), idsIn("(INTEGRATION's `ui.editor.e2e`, the editor tests)"))
        assertEquals(listOf("D4 decision", "P1 and P2 phases"), idsIn("the D4 decision of the P1 and P2 phases"))
        assertEquals(listOf("sec. 3.4", "Sect. 2"), idsIn("see sec. 3.4 of the spec and Sect. 2"))
        assertEquals(listOf("record 0010", "decision record 10"), idsIn("as record 0010 explains, and decision record 10"))
        assertEquals(listOf("WP3", "WP-4"), idsIn("WP3 adds what WP-4 needs"))
    }

    @Test
    fun `finds numbered phases, increments, milestones, iterations and sprints`() {
        assertEquals(
            listOf("phase 1", "Phase-2", "increment 3", "Increments 4", "milestone two", "iteration 7", "Sprint Ten", "phase 0"),
            idsIn("Done in phase 1 and Phase-2, increment 3, Increments 4 and 5, milestone two, iteration 7, Sprint Ten and phase 0."),
        )
        assertEquals(listOf("phase-1", "increment-zero"), idsIn("the phase-1 specification and the increment-zero skeleton"))
        assertEquals(listOf("phases  3"), idsIn("kept from phases  3 to 5"))
    }

    @Test
    fun `ordinary prose is no reference id`() {
        listOf(
            "the decision that text files are the source of truth",
            "the time budget for parsing a large model file",
            "a section of the specification, read 3 times",
            "the color 0xFFD1E0, the D10 grid, the key Ctrl+D and 2D1 coordinates",
            "D9 and D0 are no decision keys",
            "a WP- prefix alone, ADR without a number, the § sign alone",
            "D3.js and the D2 diagram language",
            "the control points P1 and P2 of a curve, 1E7 iterations, F3 and (F3) find the next match",
            "the work package that adds find, the work-package plan",
            "the diagnostics `descriptor-missing-file` and \"context-no-metamodel\" are findings",
            "after the review, follow-up work; the reviewed code",
            "One refinement of step 2: a comment piece; the steps 1 to 3 of the algorithm",
            "ANTLR's conjured tokens (ANTLR's console listener), the enclosing TRANSITION's clause, the CLI's report",
            "the format (TEXT), the parser records 3 errors, a record of 2 entries, 5 sec timeout",
            "the phase becomes active, an increment of one, each iteration once, 3 milestones, a sprint to the end",
            "a phase-locked loop, an increment-only counter, the tenth iteration, iteration 1E7 and phase 10x",
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
