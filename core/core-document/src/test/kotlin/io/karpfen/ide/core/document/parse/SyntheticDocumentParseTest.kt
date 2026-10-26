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
 * in every build; the timing itself is tagged `budget`, and no test run executes it.
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
