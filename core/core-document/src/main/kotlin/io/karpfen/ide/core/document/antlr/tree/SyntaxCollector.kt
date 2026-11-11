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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SemanticSpan
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SymbolReference
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextRange

/**
 * Accumulates what the tree walkers of one analysis extract and assembles the [SyntaxAnalysis]. It collects
 * declarations and references for the [SyntaxIndex], and semantic spans. Names come as [QuotedString]s. A name that is
 * missing in broken text is null and never yields a reference or a span. A declaration without a name gets the empty
 * name at the start of its construct, so the structure around it survives while the user types.
 *
 * Embedded code has one owner, the lexical EVAL scanner that the tokenizer uses too. Whatever a walker reports inside a
 * region for which [isEmbedded] answers true is dropped, because it is what error recovery made of Python text. Only
 * [embeddedReference]s are recorded there. The class is not thread-safe. Use one instance per analysis.
 *
 * @param isEmbedded true for a range that lies inside embedded code, which is an EVAL body.
 */
internal class SyntaxCollector(private val isEmbedded: (TextRange) -> Boolean = { false }) {

    private val declarations = ArrayList<SymbolDeclaration>()
    private val references = ArrayList<SymbolReference>()
    private val spans = ArrayList<SemanticSpan>()

    /**
     * Declares a symbol named by the string literal [name] whose whole construct is [construct]. The range of the
     * declaration covers the name as well, which clamps it for conjured tokens. [span] is the semantic kind of the
     * name, if any.
     */
    fun declare(
        kind: SymbolKind,
        name: QuotedString?,
        construct: TextRange,
        container: String?,
        typeName: String? = null,
        span: SemanticKind? = null,
    ) {
        span(name?.inner, span)
        declare(kind, name?.value.orEmpty(), name?.inner ?: TextRange.empty(construct.start), construct, container, typeName)
    }

    /** Declares a symbol whose name is not a string literal, such as the `STATEMACHINE` keyword. */
    fun declare(
        kind: SymbolKind,
        name: String,
        nameRange: TextRange,
        construct: TextRange,
        container: String?,
        typeName: String? = null,
    ) {
        if (isEmbedded(nameRange)) return
        declarations += SymbolDeclaration(kind, name, nameRange, construct.union(nameRange), container, typeName)
    }

    /** Records a reference to the name inside [name]. A missing name records nothing. */
    fun reference(kind: ReferenceKind, name: QuotedString?, qualifier: String? = null, span: SemanticKind? = null) {
        if (name != null) reference(kind, name.value, name.inner, qualifier, span)
    }

    /** Records a reference named [name] at [range], such as a model path spelled without blanks. */
    fun reference(kind: ReferenceKind, name: String, range: TextRange, qualifier: String? = null, span: SemanticKind? = null) {
        if (!isEmbedded(range)) record(SymbolReference(kind, name, range, qualifier), span)
    }

    /**
     * Records a reference to the type named inside [name]. For `number`, `string` and `boolean` it is a
     * [ReferenceKind.PRIMITIVE_TYPE] with a [SemanticKind.PRIMITIVE_TYPE] span. Otherwise it is a [ReferenceKind.TYPE]
     * with a [SemanticKind.TYPE_NAME] span. A missing name records nothing.
     */
    fun typeReference(name: QuotedString?) {
        val value = name?.value ?: return
        if (value in KarpfenSyntax.PRIMITIVE_TYPES) {
            reference(ReferenceKind.PRIMITIVE_TYPE, name, span = SemanticKind.PRIMITIVE_TYPE)
        } else {
            reference(ReferenceKind.TYPE, name, span = SemanticKind.TYPE_NAME)
        }
    }

    /** Records a reference that the embedded-code scanner found in a piece of an EVAL body, with an optional [span]. */
    fun embeddedReference(kind: ReferenceKind, name: String, range: TextRange, span: SemanticKind? = null) {
        record(SymbolReference(kind, name, range, qualifier = null), span)
    }

    /**
     * Records a semantic span. It is ignored for a missing or empty [range], for a null [kind] and inside embedded
     * code.
     */
    fun span(range: TextRange?, kind: SemanticKind?) {
        if (range != null && kind != null && !range.isEmpty && !isEmbedded(range)) spans += SemanticSpan(range, kind)
    }

    /** The index of everything declared and referenced so far. */
    fun index(): SyntaxIndex = SyntaxIndex(declarations.toList(), references.toList())

    /**
     * The spans so far, sorted by start. A span that overlaps an earlier one is dropped. By grammar, spans never
     * overlap, so this is only a guard.
     */
    fun spans(): List<SemanticSpan> {
        val sorted = spans.sortedWith(compareBy<SemanticSpan> { it.range.start }.thenBy { it.range.end })
        val result = ArrayList<SemanticSpan>(sorted.size)
        for (span in sorted) {
            if (result.isEmpty() || result.last().range.end <= span.range.start) result += span
        }
        return result
    }

    /**
     * Assembles the analysis. [folding] is deduplicated and sorted by start and then by end. Spans and index come from
     * this collector.
     */
    fun toAnalysis(diagnostics: List<Diagnostic>, outline: List<OutlineNode>, folding: List<FoldingRange>): SyntaxAnalysis =
        SyntaxAnalysis(
            syntaxDiagnostics = diagnostics.sortedWith(Diagnostic.ORDER),
            outline = outline,
            index = index(),
            folding = folding.distinct().sortedWith(compareBy<FoldingRange> { it.range.start }.thenBy { it.range.end }),
            semanticSpans = spans(),
        )

    /** Adds [reference] and, if its range is not empty, its semantic [span]. */
    private fun record(reference: SymbolReference, span: SemanticKind?) {
        references += reference
        if (span != null && !reference.range.isEmpty) spans += SemanticSpan(reference.range, span)
    }
}
