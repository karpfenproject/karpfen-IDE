package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.json.JsonParser
import io.karpfen.ide.core.document.json.containers
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The syntax analysis of environment descriptors: [JsonParser] reads the text into a tree, also a broken one, and the
 * tree yields the syntax diagnostics (the lexer's and the parser's), the folding regions (every object and list that is
 * closed and spans several lines), and, from one walk of the tree along the [schema] ([shapedValues]), the outline
 * ([DescriptorOutline]) and the semantic spans ([DescriptorSpans]). A descriptor declares and references no symbols,
 * so its syntax index is empty; what its strings name is resolved by the language services. Stateless and thread-safe.
 *
 * The descriptor checks beyond syntax (unknown and missing keys, values of the wrong kind, missing files) belong to the
 * workspace, which reads every descriptor against the same schema and reports them to the open editor as project
 * diagnostics.
 */
internal class EnvironmentSyntaxAnalyzer(private val schema: ValueShape.Record = EnvironmentSchema.ROOT) : SyntaxAnalyzer {

    override val language: KarpfenLanguage = KarpfenLanguage.ENVIRONMENT

    override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis {
        val document = JsonParser.parse(snapshot.text)
        val values = document.root?.let { shapedValues(it, schema) }.orEmpty()
        return SyntaxAnalysis(
            syntaxDiagnostics = document.diagnostics,
            outline = DescriptorOutline.of(values),
            index = SyntaxIndex.EMPTY,
            folding = document.root?.let { folding(snapshot, it) }.orEmpty(),
            semanticSpans = DescriptorSpans.of(values),
        )
    }

    /** A BLOCK region for every closed object and list below and including [root] that spans several lines. */
    private fun folding(snapshot: TextSnapshot, root: JsonNode): List<FoldingRange> =
        root.containers()
            .filter { it.isClosed && isMultiLine(snapshot, it.range) }
            .map { FoldingRange(it.range, FoldingKind.BLOCK) }
            .sortedBy { it.range.start }

    private fun isMultiLine(snapshot: TextSnapshot, range: TextRange): Boolean =
        snapshot.lineIndex.lineOf(range.start) < snapshot.lineIndex.lineOf(range.end)
}
