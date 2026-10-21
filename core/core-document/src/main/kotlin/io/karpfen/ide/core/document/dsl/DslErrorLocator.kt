package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Finds the best range for a dsl-tools exception message, which carries no position (spec §3.3, best effort):
 * the name range of a reference or declaration in [SyntaxIndex] whose name appears quoted (`'x'` or `"x"`) in the
 * message; otherwise the first token of the file (the first character that is neither whitespace nor part of a `//`
 * comment, through the end of its word); the empty range at 0 for empty text.
 *
 * dsl-tools quotes the offending name first and its context after it ("Unknown property 'z' for class type 'Point' in
 * object 'p2'"), so the candidates are the symbols of the name quoted first that names a symbol, and among them wins,
 * in this order:
 * 1. one inside a declaration another quoted name of the message names, the closest first (the fewest declarations in
 *    between: a key of the object `p2` itself before a key of an object nested in `p2`);
 * 2. a reference qualified with a quoted name (a KModel key of the type `Point`);
 * 3. a declaration the languages' own facts already reject (a KMeta `prop` whose type is not a primitive);
 * 4. the first in document order.
 */
public fun interface DslErrorLocator {
    /** The range for [message] (null message: no exception text) in [source]. Never throws. */
    public fun locate(message: String?, source: TextSnapshot, index: SyntaxIndex): TextRange
}

/** The default [DslErrorLocator] (quoted-name search, see the interface documentation). */
public fun DslErrorLocator(): DslErrorLocator = QuotedNameErrorLocator()
