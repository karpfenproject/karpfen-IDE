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
 * @property contexts the explicit contexts, one per descriptor and ordered by descriptor path, followed by the
 *   implicit ones, ordered by directory path.
 * @property diagnostics the findings of each file, such as a descriptor or a metamodel. These are descriptor findings
 *   and context diagnostics such as `context-multiple-metamodels` or `descriptor-missing-file`. Each list is sorted
 *   with [Diagnostic.ORDER].
 * @property looseFiles every karpfen source of the tree that is in the [ModelingContext.files] of no context, in tree
 *   order. These are the files in a directory covered by a descriptor that no descriptor names, and the metamodels
 *   that an implicit context did not choose because of `context-multiple-metamodels`. They are tokenized and parsed
 *   like every file, so their syntax diagnostics appear in the Problems view and in the command line tool. dsl-tools
 *   never reads them. This holds in the IDE and in the command line tool alike.
 */
public data class ContextResolution(
    public val contexts: List<ModelingContext>,
    public val diagnostics: Map<Path, List<Diagnostic>>,
    public val looseFiles: List<Path> = emptyList(),
) {
    /** The contexts that contain [path]. A file may belong to several explicit contexts. */
    public fun contextsOf(path: Path): List<ModelingContext> = contexts.filter { path in it }

    public companion object {
        /** No contexts and no diagnostics. */
        public val EMPTY: ContextResolution = ContextResolution(emptyList(), emptyMap())
    }
}
