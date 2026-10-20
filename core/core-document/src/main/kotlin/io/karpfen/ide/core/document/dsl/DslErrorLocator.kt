package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Finds the best range for a dsl-tools exception message, which carries no position (spec §3.3, best effort):
 * the name range of the reference or declaration in [SyntaxIndex] whose name appears quoted (`'x'` or `"x"`) in the
 * message (the first such symbol in document order); otherwise the first token of the file (the first character that
 * is neither whitespace nor part of a `//` comment, through the end of its word); the empty range at 0 for empty text.
 */
public fun interface DslErrorLocator {
    /** The range for [message] (null message: no exception text) in [source]. Never throws. */
    public fun locate(message: String?, source: TextSnapshot, index: SyntaxIndex): TextRange
}

/** The default [DslErrorLocator] (quoted-name search, see the interface documentation). */
public fun DslErrorLocator(): DslErrorLocator = QuotedNameErrorLocator()
