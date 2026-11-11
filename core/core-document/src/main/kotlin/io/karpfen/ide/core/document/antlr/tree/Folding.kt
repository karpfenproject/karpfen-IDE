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

import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import org.antlr.v4.runtime.Token

/**
 * BLOCK folding, which folds every brace pair that spans more than one line. Braces are paired lexically on the
 * default-channel tokens of the parser's stream, which are the braces the parser sees. Folding therefore also works on
 * broken text. An unmatched brace folds nothing. The range runs from the `{` through the `}`.
 */
internal object BlockFolding {

    /**
     * The blocks of [tree] whose braces have the token types [open] and [close]. Braces at token indices for which
     * [skip] answers true are ignored. The braces of EVAL bodies are an example, because they fold as embedded code.
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
 * COMMENT folding, which folds runs of at least two comment lines on consecutive lines. A comment line is a line whose
 * first non-blank character starts a `//` comment. A trailing comment after code ends a run, and so does a blank line.
 * The range runs from the start of the first comment to the end of the last one.
 */
internal object CommentFolding {

    /**
     * The runs among [comments]. These are the sorted `//` comments of [snapshot], taken for example from `LexerGaps`
     * or from hidden tokens.
     */
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

    /** True if only blanks precede [offset] on its line, so that a comment there is a comment line. */
    private fun startsLine(snapshot: TextSnapshot, offset: Int): Boolean {
        val lineStart = snapshot.lineIndex.lineStart(snapshot.lineIndex.lineOf(offset))
        return (lineStart until offset).all { snapshot.text[it].let { char -> char == ' ' || char == '\t' || char == '\r' } }
    }
}
