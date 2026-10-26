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
package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/** A dotted name used in code, e.g. `javax.swing.JFrame` in `val frame = javax.swing.JFrame()`, and its 1-based line. */
internal data class QualifiedReference(val name: String, val line: Int) {
    override fun toString(): String = "$name (line $line)"
}

/**
 * Finds fully qualified references in Kotlin source text.
 *
 * Import rules alone miss references such as `java.awt.Toolkit.getDefaultToolkit()` or `javax.swing.JPanel()`, which
 * compile without an import because the JDK is on every classpath. This scanner reports every dotted name in the code
 * of a file, as [KotlinLexer] separates it from comments and literal text, outside `package` and `import` directives,
 * so the layer rules can check them against the same forbidden package prefixes as the imports. The expressions of
 * string templates are code and are seen; a name split across lines is not.
 */
internal object SourceReferences {

    /** Two or more identifier segments joined by dots, starting at a name boundary: not right after a word or a dot. */
    private val dottedName = Regex("""(?<![\w.])[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+""")

    /** A `package` or `import` directive line, whose names are not references in code. */
    private val directive = Regex("""^\s*(?:package|import)\s""")

    /** Dotted names used in the code of [source] (see the object documentation for what is skipped). */
    fun qualifiedReferences(source: String): List<QualifiedReference> =
        KotlinLexer.code(source).lines().flatMapIndexed { index, line ->
            if (directive.containsMatchIn(line)) {
                emptyList()
            } else {
                dottedName.findAll(line).map { QualifiedReference(it.value, index + 1) }.toList()
            }
        }
}

/** Fully qualified references in this file's code (see [SourceReferences]). */
internal val KoFileDeclaration.qualifiedReferences: List<QualifiedReference>
    get() = SourceReferences.qualifiedReferences(text)
