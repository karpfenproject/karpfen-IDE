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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.files.FileKind

/**
 * Checks that fragments and their files agree by name. A fragment, `STATEMACHINE "name" FRAGMENT`, lives in a file
 * named after it, `<name>.frag.kstates`, because container states find it by that name and the modeler finds its file
 * the same way. The rule is a convention of the IDE and no rule of the language: the runtime knows a fragment by the
 * name in its header and never sees a file name. So both findings are WARNINGs with the code `fragment-file-name`.
 *
 * - A fragment in a file with another name gets the warning at the name in its header. This covers a fragment in a
 *   plain `.kstates` file as well.
 * - A `.frag.kstates` file whose text declares a state machine that is no fragment gets the warning at the start of
 *   that declaration.
 *
 * The names come from the IDE's own syntax index of each file, so the check works while dsl-tools cannot read the file.
 * A fragment without a name and a file that declares no state machine yet are left alone, because the reader of
 * dsl-tools or the next edit says more about them.
 *
 * @property diagnostics where the findings go.
 */
internal class FragmentFileNames(private val diagnostics: DiagnosticsByFile) {

    /** Checks [file], if it holds KStates text. */
    fun check(file: AnalyzedFile) {
        if (file.kind.language != KarpfenLanguage.KSTATES) return
        val fileName = file.path.fileName?.toString() ?: return
        val index = file.parse.index
        val fragment = index.declarationsOf(SymbolKind.FRAGMENT).firstOrNull()
        if (fragment != null) {
            val expected = fragment.name + FileKind.FRAGMENT_SUFFIX
            if (fragment.name.isNotEmpty() && !fileName.equals(expected, ignoreCase = true)) {
                diagnostics.add(file.path, warning(fragment.nameRange, "The fragment '${fragment.name}' is expected in a file named '$expected'."))
            }
            return
        }
        val machine = index.declarationsOf(SymbolKind.STATEMACHINE).firstOrNull() ?: return
        if (file.kind == FileKind.FRAGMENT) {
            diagnostics.add(
                file.path,
                warning(
                    machine.nameRange,
                    "A ${FileKind.FRAGMENT_SUFFIX} file holds a fragment, STATEMACHINE \"name\" FRAGMENT. " +
                        "Rename the file to .kstates or make the state machine a fragment.",
                ),
            )
        }
    }

    private fun warning(range: TextRange, message: String): Diagnostic =
        Diagnostic(range, Severity.WARNING, message, WorkspaceDiagnosticCodes.FRAGMENT_FILE_NAME, DiagnosticSource.IDE)
}
