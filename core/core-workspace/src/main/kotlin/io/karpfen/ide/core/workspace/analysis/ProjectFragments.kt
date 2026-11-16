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

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import java.nio.file.Path

/**
 * A fragment that a file of the project declares, `STATEMACHINE "name" FRAGMENT`.
 *
 * @property file the analyzed file that declares it.
 * @property declaration its declaration in the syntax index of [file]. Its name is the name of the fragment.
 */
public data class DeclaredFragment(public val file: AnalyzedFile, public val declaration: SymbolDeclaration) {
    /** The name of the fragment. */
    public val name: String get() = declaration.name

    /** The path of the file that declares the fragment. */
    public val path: Path get() = file.path
}

/**
 * Answers which fragments the files of a project declare and which files use them, as far as the IDE needs it to find
 * its way between the pages of a state machine: completion and go to definition of the name after `USE`, the choice of
 * a fragment in the statechart and the step from a container state to the diagram of its fragment and back.
 *
 * Container states find a fragment by its name only. Where two files declare the same name, the verifier of dsl-tools
 * reports it. Until that is fixed, a fragment of the modeling context of the file that asks wins over one of another
 * context, and within that the first file in the order of [ProjectAnalysis.files] wins.
 *
 * The answers come from the IDE's own syntax index of each analyzed file, never from the models that dsl-tools read, so
 * they are known while a file has errors. The class is immutable and thread-safe, like the analysis it reads.
 */
public class ProjectFragments(private val analysis: ProjectAnalysis) {

    /** Every fragment with a name, in the order of [ProjectAnalysis.files]. */
    public fun all(): List<DeclaredFragment> =
        analysis.files.values.flatMap { file ->
            file.parse.index.declarationsOf(SymbolKind.FRAGMENT).filter { it.name.isNotEmpty() }.map { DeclaredFragment(file, it) }
        }

    /** The names of every fragment, sorted, each once. */
    public fun names(): List<String> = all().map { it.name }.distinct().sorted()

    /**
     * The fragment named [name] as the file at [near] finds it: one of the modeling context of [near] first, else any
     * of the project. Null if no file declares the name.
     */
    public fun named(name: String, near: Path? = null): DeclaredFragment? {
        val candidates = all().filter { it.name == name }
        val context = near?.let(analysis::contextOf)?.context
        return candidates.firstOrNull { context != null && it.path in context } ?: candidates.firstOrNull()
    }

    /**
     * The files of the project whose container states use the fragment [name] after `USE`, in the order of
     * [ProjectAnalysis.files]. A fragment that uses itself is not among its own users.
     */
    public fun usersOf(name: String): List<Path> =
        analysis.files.values
            .filter { file ->
                file.parse.index.references(ReferenceKind.FRAGMENT, name).isNotEmpty() &&
                    file.parse.index.declarations(SymbolKind.FRAGMENT, name).isEmpty()
            }
            .map { it.path }
}
