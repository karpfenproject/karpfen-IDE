package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/** [DslErrorLocator] that searches the symbols whose names appear quoted in the message. */
internal class QuotedNameErrorLocator : DslErrorLocator {
    override fun locate(message: String?, source: TextSnapshot, index: SyntaxIndex): TextRange =
        TODO("WP-PARSE: locate quoted names of the message in the index, else the first token")
}
