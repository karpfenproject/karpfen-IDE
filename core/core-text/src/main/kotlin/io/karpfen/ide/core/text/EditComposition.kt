package io.karpfen.ide.core.text

/**
 * Composes two scripts into one: the result, applied to a text of length [baseLength], gives the same text as applying
 * this script and then [next] (whose edits are in the coordinates of this script's result).
 *
 * The undo history uses it to keep a step that consists of several changes (a group, a coalesced typing run) as one
 * forward and one backward script, so undo and redo apply it as exactly one change. The result is minimal with respect
 * to the characters of the base text that survive both scripts: everything in between becomes one edit.
 *
 * @throws IllegalArgumentException if [next] reaches past the end of this script's result.
 */
internal fun EditScript.then(next: EditScript, baseLength: Int): EditScript {
    if (next.isEmpty) return this
    if (isEmpty) return next
    val pieces = PieceCursor(piecesOver(baseLength))
    val composed = ArrayList<Piece>()
    var position = 0
    for (edit in next.edits) {
        pieces.copy(edit.range.start - position, composed)
        if (edit.newText.isNotEmpty()) composed += Piece.Inserted(edit.newText)
        pieces.skip(edit.range.length)
        position = edit.range.end
    }
    pieces.copyRest(composed)
    return composed.toScript(baseLength)
}

/** One part of a text described relative to a base text: base characters kept as they were, or inserted text. */
private sealed interface Piece {
    /** The number of characters this piece contributes to the text. */
    val length: Int

    /** The characters `[from, to)` of this piece, as a piece of the same kind. */
    fun slice(from: Int, to: Int): Piece

    /** The base text's characters `[start, end)`. */
    data class Kept(val start: Int, val end: Int) : Piece {
        override val length: Int get() = end - start

        override fun slice(from: Int, to: Int): Piece = Kept(start + from, start + to)
    }

    /** Text that is not in the base text. */
    data class Inserted(val text: String) : Piece {
        override val length: Int get() = text.length

        override fun slice(from: Int, to: Int): Piece = Inserted(text.substring(from, to))
    }
}

/** The result of this script described as pieces of the base text (of length [baseLength]) and inserted text. */
private fun EditScript.piecesOver(baseLength: Int): List<Piece> {
    val pieces = ArrayList<Piece>(2 * edits.size + 1)
    var position = 0
    for (edit in edits) {
        if (edit.range.start > position) pieces += Piece.Kept(position, edit.range.start)
        if (edit.newText.isNotEmpty()) pieces += Piece.Inserted(edit.newText)
        position = edit.range.end
    }
    if (position < baseLength) pieces += Piece.Kept(position, baseLength)
    return pieces
}

/** Turns pieces back into the edits of the base text: every gap between kept parts becomes one edit. */
private fun List<Piece>.toScript(baseLength: Int): EditScript {
    val edits = ArrayList<TextEdit>()
    val pending = StringBuilder()
    var position = 0
    for (piece in this) {
        when (piece) {
            is Piece.Inserted -> pending.append(piece.text)
            is Piece.Kept -> {
                if (piece.start > position || pending.isNotEmpty()) {
                    edits += TextEdit(TextRange(position, piece.start), pending.toString())
                    pending.setLength(0)
                }
                position = piece.end
            }
        }
    }
    if (position < baseLength || pending.isNotEmpty()) edits += TextEdit(TextRange(position, baseLength), pending.toString())
    return EditScript.of(edits, baseLength)
}

/** Reads a list of pieces front to back by length, splitting a piece where a read ends inside it. */
private class PieceCursor(private val pieces: List<Piece>) {
    private var index = 0
    private var offsetInPiece = 0

    /** Appends the next [length] characters' pieces to [target]. */
    fun copy(length: Int, target: MutableList<Piece>) = advance(length, target)

    /** Skips the next [length] characters. */
    fun skip(length: Int) = advance(length, target = null)

    /** Appends every remaining piece to [target]. */
    fun copyRest(target: MutableList<Piece>) {
        if (index < pieces.size && offsetInPiece > 0) {
            val piece = pieces[index++]
            target += piece.slice(offsetInPiece, piece.length)
            offsetInPiece = 0
        }
        while (index < pieces.size) target += pieces[index++]
    }

    private fun advance(length: Int, target: MutableList<Piece>?) {
        var remaining = length
        while (remaining > 0) {
            require(index < pieces.size) { "the second script reaches past the end of the first script's result" }
            val piece = pieces[index]
            val taken = minOf(remaining, piece.length - offsetInPiece)
            target?.add(if (taken == piece.length) piece else piece.slice(offsetInPiece, offsetInPiece + taken))
            offsetInPiece += taken
            remaining -= taken
            if (offsetInPiece == piece.length) {
                index++
                offsetInPiece = 0
            }
        }
    }
}
