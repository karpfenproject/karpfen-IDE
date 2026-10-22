package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import kstates.KstatesParser

/**
 * The KStates reference rules shared by the action, transition and condition walkers, so each construct is
 * recorded the same way wherever it occurs:
 * - a model-path string → MODEL_PATH reference named by the path with blanks removed
 *   (`KarpfenSyntax.normalizeModelPath`: `a -> b` → `a->b`, as for `$( … )` in EVAL bodies), range inside the quotes,
 *   MODEL_PATH span;
 * - `MACRO("m", args…)` → MACRO reference with a MACRO_NAME span plus a model path per argument;
 * - `EVENT("d", "n")` → EVENT_DOMAIN reference (EVENT_DOMAIN span) and EVENT_NAME reference qualified with the domain
 *   (EVENT_NAME span).
 */
internal class KstatesReferences(private val tree: ParsedTree<*>, private val collector: SyntaxCollector) {

    /** Records [path] as a model path (nothing for a missing string). */
    fun modelPath(path: QuotedString?) {
        if (path != null) {
            collector.reference(ReferenceKind.MODEL_PATH, KarpfenSyntax.normalizeModelPath(path.value), path.inner, span = SemanticKind.MODEL_PATH)
        }
    }

    /** Records a macro call; returns the called macro's name (null if missing). */
    fun macroCall(ctx: KstatesParser.Macro_callContext?): String? {
        val strings = tree.quotedStrings(ctx, KstatesParser.STRING)
        val macro = strings.firstOrNull()
        collector.reference(ReferenceKind.MACRO, macro, span = SemanticKind.MACRO_NAME)
        strings.drop(1).forEach(::modelPath)
        return macro?.value
    }

    /** Records the domain and the name of an `EVENT(…)` clause or action. */
    fun event(domain: QuotedString?, name: QuotedString?) {
        collector.reference(ReferenceKind.EVENT_DOMAIN, domain, span = SemanticKind.EVENT_DOMAIN)
        collector.reference(ReferenceKind.EVENT_NAME, name, qualifier = domain?.value, span = SemanticKind.EVENT_NAME)
    }
}
