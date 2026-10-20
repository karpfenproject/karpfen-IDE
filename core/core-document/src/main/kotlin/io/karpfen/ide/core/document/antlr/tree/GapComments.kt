package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.antlr.LexerGaps
import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.Token

/**
 * The `//` comments of a KMeta or KModel tree, whose lexers skip them: the [LexerGaps.GapKind.COMMENT] pieces of
 * [LexerGaps.scan] between the stream's tokens (the same pieces the tokenizer turns into COMMENT tokens).
 */
internal object GapComments {

    /** The comment ranges of [tree], in document order. */
    fun of(tree: ParsedTree<*>): List<TextRange> {
        val tokenRanges = tree.tokens.filter { it.type != Token.EOF }.mapNotNull { tree.rangeOf(it) }
        return LexerGaps.scan(tree.text, tokenRanges).filter { it.kind == LexerGaps.GapKind.COMMENT }.map { it.range }
    }
}
