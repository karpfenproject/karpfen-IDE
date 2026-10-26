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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.diagnostics.DiagnosticCode
import io.karpfen.dsl.diagnostics.Severity
import io.karpfen.dsl.source.SourcePosition
import io.karpfen.dsl.source.SourceRange
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import io.karpfen.dsl.diagnostics.Diagnostic as DslDiagnostic
import io.karpfen.ide.core.document.Severity as IdeSeverity

/** Covers [ReadResults.diagnosticOf]: offsets carry over, codes become ids, and ranges never leave the text. */
class ReadResultsTest {

    private fun range(start: Int, end: Int): SourceRange = SourceRange(SourcePosition(start, 1, start + 1), SourcePosition(end, 1, end + 1))

    @Test
    fun `offsets, severity, code and message carry over`() {
        val finding = DslDiagnostic(range(3, 7), Severity.WARNING, DiagnosticCode.EMBEDDED_TYPE_MISMATCH, "embeds a Line")

        val diagnostic = ReadResults.diagnosticOf(finding, textLength = 10)

        assertEquals(TextRange(3, 7), diagnostic.range)
        assertEquals(IdeSeverity.WARNING, diagnostic.severity)
        assertEquals("kmodel.embedded-type-mismatch", diagnostic.code)
        assertEquals("embeds a Line", diagnostic.message)
        assertEquals(DiagnosticSource.DSL_TOOLS, diagnostic.source)
    }

    @Test
    fun `a range beyond the text is clamped to it`() {
        val finding = DslDiagnostic(range(8, 20), Severity.ERROR, DiagnosticCode.SYNTAX_ERROR, "missing '}'")

        assertEquals(TextRange(8, 10), ReadResults.diagnosticOf(finding, textLength = 10).range)
        assertEquals(TextRange(4, 4), ReadResults.diagnosticOf(finding, textLength = 4).range)
    }
}
