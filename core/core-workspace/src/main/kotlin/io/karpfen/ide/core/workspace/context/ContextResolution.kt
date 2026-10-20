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
 * @property looseFiles the karpfen sources of the tree that belong to no context (files in a directory covered by a
 *   descriptor that no descriptor names), in tree order; analyzed syntax-only.
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
