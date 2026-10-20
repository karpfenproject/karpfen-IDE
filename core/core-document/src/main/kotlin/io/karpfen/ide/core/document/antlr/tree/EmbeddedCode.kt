package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.antlr.EmbeddedKind
import io.karpfen.ide.core.document.antlr.EmbeddedPiece
import io.karpfen.ide.core.document.antlr.EvalBodies
import io.karpfen.ide.core.document.antlr.EvalBody
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.text.TextRange
import java.util.BitSet

/**
 * The embedded Python of a KStates tree, read with the shared lexical scanner ([EvalBodies.find]/[EvalBodies.scan]
 * over the parser stream's tokens of every channel), never from the tree, so navigation and folding agree with the
 * tokenizer's highlighting on every input (decision 16):
 * - a PATH_REFERENCE piece → MODEL_PATH reference named [EmbeddedPiece.name] at [EmbeddedPiece.inner] (MODEL_PATH span);
 * - a MACRO_REFERENCE piece → MACRO reference named after the `@` at [EmbeddedPiece.inner] (MACRO_NAME span);
 * - a closed body spanning more than one line → EMBEDDED_CODE folding from its `{` through its `}`.
 *
 * Braces of the bodies (their own and the Python ones inside) are not karpfen blocks: [isEmbeddedToken] lets
 * [BlockFolding] skip them. What error recovery makes of the Python text is not karpfen either: [isEmbedded] lets the
 * [SyntaxCollector] drop the walkers' findings inside the bodies.
 */
internal class EmbeddedCode private constructor(
    private val tree: ParsedTree<*>,
    private val bodies: List<Pair<EvalBody, List<EmbeddedPiece>>>,
) {
    /** Token indices from every body's `{` through its `}` (through the end for an unclosed body). */
    private val embeddedTokens = BitSet(tree.tokens.size).apply {
        bodies.forEach { (body, _) -> set(maxOf(0, body.firstToken - 1), minOf(tree.tokens.size, body.endToken + 1)) }
    }

    /** The bodies' content ranges, sorted and disjoint (a body nested in a body is part of the outer content). */
    private val contents: List<TextRange> = bodies.map { it.first.content }

    /** True if the token at [tokenIndex] of the stream is an EVAL body brace or lies inside a body. */
    fun isEmbeddedToken(tokenIndex: Int): Boolean = embeddedTokens[tokenIndex]

    /** True if [range] lies inside the content of a body (its bounds included). */
    fun isEmbedded(range: TextRange): Boolean {
        val candidate = contents.binarySearchBy(range.start) { it.start }.let { if (it >= 0) it else -it - 2 }
        return candidate >= 0 && range in contents[candidate]
    }

    /** Records the MODEL_PATH and MACRO references of every body in [collector]. */
    fun recordReferences(collector: SyntaxCollector) {
        for ((_, pieces) in bodies) {
            for (piece in pieces) {
                val inner = piece.inner ?: continue
                val name = piece.name ?: continue
                when (piece.kind) {
                    EmbeddedKind.PATH_REFERENCE -> collector.embeddedReference(ReferenceKind.MODEL_PATH, name, inner, SemanticKind.MODEL_PATH)
                    EmbeddedKind.MACRO_REFERENCE -> collector.embeddedReference(ReferenceKind.MACRO, name, inner, SemanticKind.MACRO_NAME)
                    else -> Unit
                }
            }
        }
    }

    /** The EMBEDDED_CODE folding ranges of the closed multi-line bodies. */
    fun folding(): List<FoldingRange> = bodies.mapNotNull { (body, _) ->
        val close = body.close ?: return@mapNotNull null
        TextRange(body.open.start, close.end)
            .takeIf { BlockFolding.isMultiLine(tree.snapshot, it) }
            ?.let { FoldingRange(it, FoldingKind.EMBEDDED_CODE) }
    }

    companion object {
        /** Scans the EVAL bodies of [tree]. */
        fun of(tree: ParsedTree<*>): EmbeddedCode = EmbeddedCode(tree, EvalBodies.scanAll(tree.text, tree.tokens, tree.offsets))
    }
}
