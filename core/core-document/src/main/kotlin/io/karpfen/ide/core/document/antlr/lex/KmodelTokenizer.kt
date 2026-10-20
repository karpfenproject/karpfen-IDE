package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * [Tokenizer] for KModel on top of the dsl-tools `kmodel.KmodelLexer` with its console error listener removed and an IDE
 * collector added (spec §3.1).
 *
 * The KModel lexer skips comments; they are recovered from the gaps between tokens (by construction of the grammar a gap
 * contains only whitespace and `//` comments) and emitted as COMMENT. Token offsets are converted with
 * `CodePointOffsets`.
 */
internal class KmodelTokenizer : Tokenizer {
    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = TODO("WP-TOKENS: tokenize KModel")
}
