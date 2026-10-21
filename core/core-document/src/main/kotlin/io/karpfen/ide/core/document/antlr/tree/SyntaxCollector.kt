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
 * Accumulates what the tree walkers of one analysis extract (spec §3.3): declarations and references for the
 * [SyntaxIndex] and semantic spans, and assembles the [SyntaxAnalysis]. Names come as [QuotedString]s; a name that is
 * missing in broken text (null) never yields a reference or a span, and a declaration without a name gets the empty
 * name at the start of its construct, so the structure around it survives while the user types.
 *
 * Embedded code has one owner, the lexical EVAL scanner (decision 16): whatever a walker reports inside a region for
 * which [isEmbedded] answers true (what error recovery made of Python text) is dropped, and only
 * [embeddedReference]s are recorded there. Not thread-safe; one instance per analysis.
 *
 * @param isEmbedded true for a range that lies inside embedded code (an EVAL body).
 */
internal class SyntaxCollector(private val isEmbedded: (TextRange) -> Boolean = { false }) {

    private val declarations = ArrayList<SymbolDeclaration>()
    private val references = ArrayList<SymbolReference>()
    private val spans = ArrayList<SemanticSpan>()

    /**
     * Declares a symbol named by the string literal [name] whose whole construct is [construct]; the declaration's
     * range covers the name as well (clamping for conjured tokens). [span] is the semantic kind of the name, if any.
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

    /** Declares a symbol whose name is not a string literal (e.g. the `STATEMACHINE` keyword). */
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

    /** Records a reference to the name inside [name] (nothing for a missing name). */
    fun reference(kind: ReferenceKind, name: QuotedString?, qualifier: String? = null, span: SemanticKind? = null) {
        if (name != null) reference(kind, name.value, name.inner, qualifier, span)
    }

    /** Records a reference named [name] at [range] (e.g. a model path spelled without blanks). */
    fun reference(kind: ReferenceKind, name: String, range: TextRange, qualifier: String? = null, span: SemanticKind? = null) {
        if (!isEmbedded(range)) record(SymbolReference(kind, name, range, qualifier), span)
    }

    /**
     * Records a reference to the type named inside [name]: [ReferenceKind.PRIMITIVE_TYPE] with a
     * [SemanticKind.PRIMITIVE_TYPE] span for `number`, `string` and `boolean`, else [ReferenceKind.TYPE] with a
     * [SemanticKind.TYPE_NAME] span. Nothing for a missing name.
     */
    fun typeReference(name: QuotedString?) {
        val value = name?.value ?: return
        if (value in KarpfenSyntax.PRIMITIVE_TYPES) {
            reference(ReferenceKind.PRIMITIVE_TYPE, name, span = SemanticKind.PRIMITIVE_TYPE)
        } else {
            reference(ReferenceKind.TYPE, name, span = SemanticKind.TYPE_NAME)
        }
    }

    /** Records a reference found by the embedded-code scanner (a piece of an EVAL body), with an optional [span]. */
    fun embeddedReference(kind: ReferenceKind, name: String, range: TextRange, span: SemanticKind? = null) {
        record(SymbolReference(kind, name, range, qualifier = null), span)
    }

    /** Records a semantic span; ignored for a missing or empty [range], a null [kind] and inside embedded code. */
    fun span(range: TextRange?, kind: SemanticKind?) {
        if (range != null && kind != null && !range.isEmpty && !isEmbedded(range)) spans += SemanticSpan(range, kind)
    }

    /** The index of everything declared and referenced so far. */
    fun index(): SyntaxIndex = SyntaxIndex(declarations.toList(), references.toList())

    /** The spans so far, sorted by start; a span overlapping an earlier one is dropped (they never overlap by grammar). */
    fun spans(): List<SemanticSpan> {
        val sorted = spans.sortedWith(compareBy<SemanticSpan> { it.range.start }.thenBy { it.range.end })
        val result = ArrayList<SemanticSpan>(sorted.size)
        for (span in sorted) {
            if (result.isEmpty() || result.last().range.end <= span.range.start) result += span
        }
        return result
    }

    /** Assembles the analysis: [folding] sorted by start (then end), spans and index from this collector. */
    fun toAnalysis(diagnostics: List<Diagnostic>, outline: List<OutlineNode>, folding: List<FoldingRange>): SyntaxAnalysis =
        SyntaxAnalysis(
            syntaxDiagnostics = diagnostics.sortedWith(Diagnostic.ORDER),
            outline = outline,
            index = index(),
            folding = folding.distinct().sortedWith(compareBy<FoldingRange> { it.range.start }.thenBy { it.range.end }),
            semanticSpans = spans(),
        )

    private fun record(reference: SymbolReference, span: SemanticKind?) {
        references += reference
        if (span != null && !reference.range.isEmpty) spans += SemanticSpan(reference.range, span)
    }
}
