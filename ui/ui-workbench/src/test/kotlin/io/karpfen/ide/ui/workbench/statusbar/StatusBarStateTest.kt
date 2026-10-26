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
package io.karpfen.ide.ui.workbench.statusbar

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.problems.FileProblems
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.workbench.output.UnseenOutputProblems
import io.karpfen.ide.ui.workbench.testing.FakeOpenDocument
import io.karpfen.ide.ui.workbench.testing.FakeWorkspace
import io.karpfen.ide.ui.workbench.testing.editorOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** [StatusBarState], [CaretPositionText] and [StatusBarSegments]. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBarStateTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()

    @Test
    fun `the status bar follows the editor's caret, settings and separator`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val status = StatusBarState(scope)
        val document = FakeOpenDocument(root.resolve("a.kstates"), "STATE\n\tab\tc\n", scope, dispatcher)
        val settings = MutableStateFlow(EditorSettings(tabDisplayWidth = 4))
        val editor = editorOf(document, scope, dispatcher, settings)

        status.bindEditor(document, editor)
        assertEquals(
            StatusBarUiState(line = 1, column = 1, language = KarpfenLanguage.KSTATES, lineSeparator = LineSeparator.LF),
            status.state.value,
        )

        val secondLineStart = "STATE\n".length
        editor.caret.select(anchor = secondLineStart, position = secondLineStart + "\tab\t".length)
        advanceUntilIdle()
        assertEquals(2, status.state.value.line)
        assertEquals(9, status.state.value.column, "display column: tab to 4, 'ab', tab to 8 → column 9")
        assertEquals(4, status.state.value.selectionLength)

        settings.value = EditorSettings(tabDisplayWidth = 2)
        document.lineSeparator.value = LineSeparator.CRLF
        advanceUntilIdle()
        assertEquals(7, status.state.value.column)
        assertEquals(LineSeparator.CRLF, status.state.value.lineSeparator)

        status.bindEditor(null, null)
        assertEquals(StatusBarUiState(), status.state.value)
        scope.cancel()
    }

    @Test
    fun `the status bar shows the workspace's counts and the analyzing flag`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val status = StatusBarState(scope)
        val workspace = FakeWorkspace(root)
        status.bindWorkspace(workspace)
        val diagnostics = listOf(Severity.ERROR, Severity.WARNING, Severity.WARNING, Severity.INFO).mapIndexed { i, severity ->
            Diagnostic(TextRange(i, i), severity, "m", "c", DiagnosticSource.PARSER)
        }
        val file = FileProblems(root.resolve("a.kmeta"), FileKind.KMETA, diagnostics, null, false)
        workspace.problems.value = WorkspaceProblems(1, listOf(file))
        workspace.isAnalyzing.value = true
        advanceUntilIdle()
        assertEquals(StatusBarUiState(errorCount = 1, warningCount = 2, isAnalyzing = true), status.state.value)

        status.bindWorkspace(null)
        assertEquals(StatusBarUiState(), status.state.value)
        scope.cancel()
    }

    @Test
    fun `the caret position clamps offsets beyond the text`() {
        val snapshot = TextSnapshot("ab\ncd", 0)
        assertEquals(CaretPositionText(2, 3, 0), CaretPositionText.of(snapshot, Caret.at(99), 4))
        assertEquals(CaretPositionText(2, 2, 3), CaretPositionText.of(snapshot, Caret(1, 4), 4))
    }

    @Test
    fun `segments render the state`() {
        val state = StatusBarUiState(
            line = 3, column = 7, selectionLength = 1, language = KarpfenLanguage.KMETA, lineSeparator = LineSeparator.CRLF,
            errorCount = 2, warningCount = 0, isAnalyzing = true,
        )
        assertEquals(listOf("Analyzing…"), StatusBarSegments.left(state).map { it.text })
        assertEquals(
            listOf("Ln 3, Col 7", "1 char", "KMeta", "CRLF", "UTF-8", "2", "0"),
            StatusBarSegments.right(state).map { it.text },
        )
        assertEquals(listOf("errors", "warnings"), StatusBarSegments.right(StatusBarUiState()).map { it.id })
        assertEquals("5 chars", StatusBarSegments.right(StatusBarUiState(selectionLength = 5)).first().text)
    }

    @Test
    fun `unseen problems of the language tools show as an indicator on the left, an error one if an error is among them`() {
        assertEquals(emptyList(), StatusBarSegments.left(StatusBarUiState()))

        val warnings = StatusBarSegments.left(StatusBarUiState(unseenOutput = UnseenOutputProblems(warnings = 2))).single()
        val mixed = StatusBarSegments.left(StatusBarUiState(isAnalyzing = true, unseenOutput = UnseenOutputProblems(1, 3))).last()

        assertEquals(StatusSegmentKind.LANGUAGE_TOOLS_WARNINGS, warnings.kind)
        assertEquals("Language tools: 2", warnings.text)
        assertEquals("0 errors and 2 warnings of the language tools; click to show their output", warnings.tooltip)
        assertEquals(StatusSegmentKind.LANGUAGE_TOOLS_ERRORS, mixed.kind)
        assertEquals("Language tools: 4", mixed.text)
        assertEquals("1 error and 3 warnings of the language tools; click to show their output", mixed.tooltip)
    }

    @Test
    fun `the status bar shows the unseen problems the mediator passes on`() = runTest {
        val status = StatusBarState(backgroundScope)

        status.showUnseenOutput(UnseenOutputProblems(errors = 1))

        assertEquals(UnseenOutputProblems(errors = 1), status.state.value.unseenOutput)
    }
}
