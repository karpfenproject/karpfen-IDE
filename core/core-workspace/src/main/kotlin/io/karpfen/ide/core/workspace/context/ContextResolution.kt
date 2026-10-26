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
package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.Diagnostic
import java.nio.file.Path

/**
 * The modeling contexts of a workspace and the findings made while resolving them.
 *
 * @property contexts explicit contexts (one per descriptor, by descriptor path) followed by implicit ones (by
 *   directory path).
 * @property diagnostics per file (descriptors, metamodels): descriptor findings and context diagnostics such as
 *   `context-multiple-metamodels` or `descriptor-missing-file`, each list sorted with [Diagnostic.ORDER].
 * @property looseFiles every karpfen source of the tree that is in no context's [ModelingContext.files], in tree order:
 *   files in a directory covered by a descriptor that no descriptor names, and the metamodels an implicit context did
 *   not choose (`context-multiple-metamodels`). Tokenized and parsed like every file (their syntax diagnostics appear in
 *   the Problems view and in the CLI), never read by dsl-tools, in the IDE and in the CLI alike.
 */
public data class ContextResolution(
    public val contexts: List<ModelingContext>,
    public val diagnostics: Map<Path, List<Diagnostic>>,
    public val looseFiles: List<Path> = emptyList(),
) {
    /** The contexts containing [path] (a file may belong to several explicit contexts). */
    public fun contextsOf(path: Path): List<ModelingContext> = contexts.filter { path in it }

    public companion object {
        /** No contexts, no diagnostics. */
        public val EMPTY: ContextResolution = ContextResolution(emptyList(), emptyMap())
    }
}
