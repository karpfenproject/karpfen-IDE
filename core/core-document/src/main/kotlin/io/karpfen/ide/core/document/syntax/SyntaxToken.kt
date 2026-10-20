package io.karpfen.ide.core.document.syntax

import io.karpfen.ide.core.text.TextRange

/** One classified, non-empty piece of a document (spec §3.1). */
public data class SyntaxToken(public val range: TextRange, public val kind: TokenKind) {
    init {
        require(!range.isEmpty) { "tokens are never empty, was $range" }
    }
}
