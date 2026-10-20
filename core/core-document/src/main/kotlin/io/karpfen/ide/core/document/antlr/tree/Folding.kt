package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import org.antlr.v4.runtime.Token

/**
 * BLOCK folding (spec §3.3): every brace pair spanning more than one line. Braces are paired lexically on the
 * default-channel tokens of the parser's stream (the same braces the parser sees), so folding also works on broken
 * text; an unmatched brace folds nothing. The range runs from the `{` through the `}`.
 */
internal object BlockFolding {

    /**
     * The blocks of [tree] whose braces have the token types [open] and [close]; braces at token indices for which
     * [skip] answers true (e.g. those of EVAL bodies, which fold as embedded code) are ignored.
     */
    fun of(tree: ParsedTree<*>, open: Int, close: Int, skip: (tokenIndex: Int) -> Boolean = { false }): List<FoldingRange> {
        val folds = ArrayList<FoldingRange>()
        val pending = ArrayDeque<TextRange>()
        tree.tokens.forEachIndexed { index, token ->
            if (token.channel != Token.DEFAULT_CHANNEL || skip(index)) return@forEachIndexed
            val range = tree.rangeOf(token) ?: return@forEachIndexed
            when (token.type) {
                open -> pending.addLast(range)
                close -> pending.removeLastOrNull()?.let { start ->
                    val block = TextRange(start.start, range.end)
                    if (isMultiLine(tree.snapshot, block)) folds += FoldingRange(block, FoldingKind.BLOCK)
                }
            }
        }
        return folds
    }

    /** True if [range] starts on one line of [snapshot] and ends on a later one. */
    fun isMultiLine(snapshot: TextSnapshot, range: TextRange): Boolean =
        snapshot.lineIndex.lineOf(range.start) < snapshot.lineIndex.lineOf(range.end)
}

/**
 * COMMENT folding (spec §3.3): runs of at least two comment lines on consecutive lines. A comment line is a line whose
 * first non-blank character starts a `//` comment; a trailing comment after code and a blank line end a run. The range
 * runs from the first comment's start to the last one's end.
 */
internal object CommentFolding {

    /** The runs among [comments] (the `//` comments of [snapshot], sorted, e.g. from `LexerGaps` or hidden tokens). */
    fun of(snapshot: TextSnapshot, comments: List<TextRange>): List<FoldingRange> {
        val folds = ArrayList<FoldingRange>()
        var run = ArrayList<TextRange>()
        var lastLine = -2
        fun flush() {
            if (run.size >= 2) folds += FoldingRange(TextRange(run.first().start, run.last().end), FoldingKind.COMMENT)
            run = ArrayList()
        }
        for (comment in comments) {
            val line = snapshot.lineIndex.lineOf(comment.start)
            if (!startsLine(snapshot, comment.start)) {
                flush()
                lastLine = -2
                continue
            }
            if (line != lastLine + 1) flush()
            run += comment
            lastLine = line
        }
        flush()
        return folds
    }

    private fun startsLine(snapshot: TextSnapshot, offset: Int): Boolean {
        val lineStart = snapshot.lineIndex.lineStart(snapshot.lineIndex.lineOf(offset))
        return (lineStart until offset).all { snapshot.text[it].let { char -> char == ' ' || char == '\t' || char == '\r' } }
    }
}
