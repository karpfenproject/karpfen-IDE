package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The large synthetic KStates document used by the (excluded) parse budget test and by the editor benchmarks is
 * syntactically valid, so those measurements time the normal path and not error recovery. This functional part runs
 * in every build; the timing itself is tagged `budget` (docs/adr/0011-time-budget-tests-are-not-run.md).
 */
class SyntheticDocumentParseTest {

    @Test
    fun `a 5000-line synthetic KStates document parses without syntax diagnostics`() {
        val text = SyntheticKstates.withMinLines(5_000)
        val parser: DocumentParser = ComposedDocumentParser(KarpfenLanguage.KSTATES, KstatesSyntaxAnalyzer(), semantics = null)

        val outcome = parser.parse(TextSnapshot(text, 0L))

        assertTrue(outcome.isSyntaxClean, "syntax diagnostics: ${outcome.syntaxDiagnostics.take(3)}")
        assertEquals(1, outcome.outline.size, "one state machine at the top level")
    }
}
