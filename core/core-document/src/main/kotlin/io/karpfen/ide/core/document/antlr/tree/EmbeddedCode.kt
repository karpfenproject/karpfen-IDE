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
 * The embedded Python of a KStates tree. It is read with the shared lexical scanner, [EvalBodies.find] and
 * [EvalBodies.scan], over the tokens of every channel in the parser's stream. It is never read from the tree, so
 * navigation and folding agree with the tokenizer's highlighting on every input, broken ones included.
 * - A PATH_REFERENCE piece becomes a MODEL_PATH reference named [EmbeddedPiece.name] at [EmbeddedPiece.inner], with a
 *   MODEL_PATH span.
 * - A MACRO_REFERENCE piece becomes a MACRO reference named after the `@` at [EmbeddedPiece.inner], with a MACRO_NAME
 *   span.
 * - A closed body that spans more than one line becomes an EMBEDDED_CODE folding from its `{` through its `}`.
 *
 * The braces of the bodies are not karpfen blocks. That holds for their own braces and for the Python braces inside.
 * [isEmbeddedToken] lets [BlockFolding] skip them. What error recovery makes of the Python text is not karpfen either.
 * [isEmbedded] lets the [SyntaxCollector] drop the findings of the walkers inside the bodies.
 */
internal class EmbeddedCode private constructor(
    private val tree: ParsedTree<*>,
    private val bodies: List<Pair<EvalBody, List<EmbeddedPiece>>>,
) {
    /** Token indices from every body's `{` through its `}`, or through the end for a body that is not closed. */
    private val embeddedTokens = BitSet(tree.tokens.size).apply {
        bodies.forEach { (body, _) -> set(maxOf(0, body.firstToken - 1), minOf(tree.tokens.size, body.endToken + 1)) }
    }

    /**
     * The content ranges of the bodies, sorted and disjoint. A body nested in a body is part of the outer body's
     * content.
     */
    private val contents: List<TextRange> = bodies.map { it.first.content }

    /** True if the token at [tokenIndex] of the stream is an EVAL body brace or lies inside a body. */
    fun isEmbeddedToken(tokenIndex: Int): Boolean = embeddedTokens[tokenIndex]

    /** True if [range] lies inside the content of a body, with the bounds of the content included. */
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
