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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.structure.MetamodelProjection
import io.karpfen.ide.notation.diagram.structure.ModelProjection
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Applies the changes of the class and object diagrams to their texts in tests and reads the result again: with the
 * parser of the editor, which must find no syntax error and gives the diagram, and with the reader of dsl-tools, which
 * shows what the language makes of the text.
 */
internal object StructureTexts {

    /** [source] with the edits of [change] applied. */
    fun applied(change: DiagramChange?, source: String): String =
        assertNotNull(change, "there is a change").edits.sortedByDescending { it.range.start }
            .fold(source) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) }

    /** The class diagram of the metamodel [source], which must have no syntax errors. */
    fun readTypes(source: String): StructureDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KMETA).parser.parse(TextSnapshot(source, 0))
        assertTrue(outcome.isSyntaxClean, "the text has syntax errors:\n$source")
        return MetamodelProjection.of(outcome.outline, source)
    }

    /** The object diagram of the model [source], which must have no syntax errors. */
    fun readObjects(source: String): StructureDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KMODEL).parser.parse(TextSnapshot(source, 0))
        assertTrue(outcome.isSyntaxClean, "the text has syntax errors:\n$source")
        return ModelProjection.of(outcome.outline)
    }

    /** The metamodel that dsl-tools reads from [source]. */
    fun metamodelOf(source: String): Metamodel = assertNotNull(DslReader().kmeta(TextSnapshot(source, 0)).model, source)

    /** The model that dsl-tools reads from [source] against the metamodel [meta], and the messages of its errors. */
    fun modelOf(source: String, meta: String): Pair<Model?, List<String>> {
        val read = DslReader().kmodel(TextSnapshot(source, 0), metamodelOf(meta))
        return read.model to read.diagnostics.filter { it.severity == Severity.ERROR }.map { it.message }
    }
}
