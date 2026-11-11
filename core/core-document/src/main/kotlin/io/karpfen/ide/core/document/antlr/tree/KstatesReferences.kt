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
package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.dsl.grammar.kstates.KstatesParser
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.syntax.KarpfenSyntax

/**
 * The KStates reference rules shared by the action, transition and condition walkers, so each construct is recorded
 * the same way wherever it occurs.
 * - A model-path string becomes a MODEL_PATH reference with a MODEL_PATH span. Its range lies inside the quotes. Its
 *   name is the path with blanks removed by `KarpfenSyntax.normalizeModelPath`, so `a -> b` becomes `a->b`, as for
 *   path references in EVAL bodies.
 * - `MACRO("m", "p1", "p2")` becomes a MACRO reference with a MACRO_NAME span, plus a model path for each argument.
 * - `EVENT("d", "n")` becomes an EVENT_DOMAIN reference with an EVENT_DOMAIN span and an EVENT_NAME reference with an
 *   EVENT_NAME span. The event name reference is qualified with the domain.
 */
internal class KstatesReferences(private val tree: ParsedTree<*>, private val collector: SyntaxCollector) {

    /** Records [path] as a model path. A missing string records nothing. */
    fun modelPath(path: QuotedString?) {
        if (path != null) {
            collector.reference(ReferenceKind.MODEL_PATH, KarpfenSyntax.normalizeModelPath(path.value), path.inner, span = SemanticKind.MODEL_PATH)
        }
    }

    /** Records a macro call. Returns the name of the called macro, or null if it is missing. */
    fun macroCall(ctx: KstatesParser.Macro_callContext?): String? {
        val strings = tree.quotedStrings(ctx, KstatesParser.STRING)
        val macro = strings.firstOrNull()
        collector.reference(ReferenceKind.MACRO, macro, span = SemanticKind.MACRO_NAME)
        strings.drop(1).forEach(::modelPath)
        return macro?.value
    }

    /** Records the domain and the name of an `EVENT("d", "n")` clause or action. */
    fun event(domain: QuotedString?, name: QuotedString?) {
        collector.reference(ReferenceKind.EVENT_DOMAIN, domain, span = SemanticKind.EVENT_DOMAIN)
        collector.reference(ReferenceKind.EVENT_NAME, name, qualifier = domain?.value, span = SemanticKind.EVENT_NAME)
    }
}
