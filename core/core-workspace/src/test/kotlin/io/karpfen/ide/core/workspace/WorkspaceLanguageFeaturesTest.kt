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
package io.karpfen.ide.core.workspace

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.core.workspace.fixtures.openDocument
import io.karpfen.ide.core.workspace.fixtures.settle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The optional language features of KStates in a workspace. Every feature is off when a workspace opens. While a
 * feature is off, dsl-tools reports every construct of it in a state machine of a context as an error
 * `kstates.feature-off` at its introducing keyword. The error appears in the analysis, in the problems and in the
 * editor of an open document. Switching features on or off analyzes again with the new set. The same words inside
 * EVAL code are never reported.
 */
class WorkspaceLanguageFeaturesTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    @Test
    fun `every construct of a feature that is off is an error at its keyword`() = runTest(timeout = 60.seconds) {
        val machine = write()
        val workspace = TestWorkspaces(this).open(root)

        val analysis = workspace.awaitAnalysis()

        val findings = featureOff(analysis.diagnosticsFor(machine))
        assertEquals(
            listOf("SHALLOW", "BREAKPOINT", "TICK", "MACRO", "MACRO", "DEEP", "BREAKPOINT", "SPLIT", "JOIN", "MACROS"),
            keywordsOf(findings, machine),
        )
        assertTrue(findings.all { it.severity == Severity.ERROR && it.source == DiagnosticSource.DSL_TOOLS })
        assertEquals(
            "Histories are switched off, so this history is not allowed. Run > Language Features switches them on for this project.",
            findings.first().message,
        )
        assertEquals(emptySet(), analysis.languageFeatures)
        assertEquals(emptySet(), workspace.languageFeatures.value)
        assertEquals(findings, featureOff(workspace.problems.value.of(machine)?.diagnostics.orEmpty()))
    }

    @Test
    fun `switching features on and off analyzes again with the new set`() = runTest(timeout = 60.seconds) {
        val machine = write()
        val workspace = TestWorkspaces(this).open(root)
        workspace.awaitAnalysis()

        workspace.setLanguageFeatures(TestWorkspaces.ALL_FEATURES)
        settle()

        val all = workspace.analysis.value
        assertEquals(TestWorkspaces.ALL_FEATURES, all.languageFeatures, "the change alone analyzed again")
        assertEquals(
            listOf(EVAL_WITHOUT_RETURN),
            all.diagnosticsFor(machine).map { it.code },
            "with every feature on, only the warning about the EVAL code without a return is left",
        )
        assertEquals(TestWorkspaces.ALL_FEATURES, workspace.languageFeatures.value)

        workspace.setLanguageFeatures(TestWorkspaces.ALL_FEATURES - KstatesFeature.PARALLEL)
        settle()

        val left = featureOff(workspace.analysis.value.diagnosticsFor(machine))
        assertEquals(listOf("SPLIT", "JOIN"), keywordsOf(left, machine), "the remembered read of the old set is not reused")
    }

    @Test
    fun `an open document shows the errors of its editor text`() = runTest(timeout = 60.seconds) {
        val machine = write()
        val workspace = TestWorkspaces(this).open(root, languageFeatures = TestWorkspaces.ALL_FEATURES - KstatesFeature.BREAKPOINTS)
        val document = workspace.openDocument(machine)

        workspace.awaitAnalysis()
        runCurrent()

        val shown = featureOff(document.session.diagnostics.value.diagnostics)
        assertEquals(listOf("BREAKPOINT", "BREAKPOINT"), keywordsOf(shown, machine))
    }

    /** The errors among [diagnostics] that report a construct of a feature that is off. */
    private fun featureOff(diagnostics: List<Diagnostic>): List<Diagnostic> = diagnostics.filter { it.code == FEATURE_OFF }

    /** The words of the text of [file] that [diagnostics] mark, in order. */
    private fun keywordsOf(diagnostics: List<Diagnostic>, file: Path): List<String> {
        val text = Files.readString(file)
        return diagnostics.map { text.substring(it.range.start, it.range.end) }
    }

    /**
     * Writes a context of a metamodel and a state machine that uses every optional feature, with the same words in
     * EVAL code, and returns the path of the state machine.
     */
    private fun write(): Path {
        Files.writeString(root.resolve("m.kmeta"), "type \"Lamp\" \"l\" {\n    prop(\"brightness\", \"number\")\n}\n")
        return root.resolve("m.kstates").also { file ->
            Files.writeString(
                file,
                """
                STATEMACHINE ATTACHED TO "Lamp" {
                    STATES {
                        INITIAL SHALLOW HISTORY STATE "a" {
                            BREAKPOINT { ENTRY }
                            TICK LIMIT { DELAY: 2 TICKS }
                            ENTRY { SET("brightness", MACRO("half", "brightness")) WITH MACRO("half", "x") AS "h" { SET("y", "h") } }
                            STATE "x" { }
                        }
                        DEEP HISTORY STATE "b" { STATE "y" { DO { SET("z", EVAL { DEEP = "SPLIT" }) } } }
                        STATE "c" { }
                    }
                    TRANSITIONS {
                        TRANSITION "a" -> "b" { BREAKPOINT { START } }
                        SPLIT "b" -> "a", "c" { }
                        JOIN "a", "c" -> "b" { }
                    }
                    MACROS {
                        MACRO "half" { TAKES("v", "number") RETURNS("number") DEFINITION { EVAL { return v / 2 } } }
                    }
                }
                """.trimIndent() + "\n",
            )
        }
    }

    private companion object {
        /** The dsl-tools code of a construct of a feature that is off. */
        const val FEATURE_OFF = "kstates.feature-off"

        /** The dsl-tools code of EVAL code without a `return`, which the machine of [write] has once. */
        const val EVAL_WITHOUT_RETURN = "kstates.eval-without-return"
    }
}
