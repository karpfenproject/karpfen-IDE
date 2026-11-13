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
package io.karpfen.ide.core.text

/**
 * A piece of text put in its place in a whole text, together with the mapping of positions between the two. The piece
 * is what a user edits on its own, such as the code of one block of a file. The whole is the text the piece belongs
 * to, with the piece written into it by the rule of whoever made the frame. That rule may change the piece on its way
 * into the whole, for example by indenting its lines or by leaving out blank lines around it, so the mapping is not a
 * plain shift.
 *
 * Offsets of the piece are in the coordinates of the piece text the frame was made for, offsets of the whole in the
 * coordinates of [whole].
 */
public interface TextFrame {

    /** The whole text with the piece in its place. */
    public val whole: String

    /**
     * The range of [whole] that the piece covers: from where its first character stands to where its last character
     * ends. A piece without any character covers the empty range at the place where its characters would stand.
     */
    public val region: TextRange

    /**
     * The offset of [whole] at which the offset [offset] of the piece stands. An offset in a part of the piece that
     * the whole leaves out, such as indentation that is taken off, lands on the nearest place of the whole that
     * belongs to the same line of the piece. The result always lies in [region].
     */
    public fun toWhole(offset: Int): Int

    /**
     * The range of the piece that the range [range] of [whole] covers. A range that reaches beyond [region] is clipped
     * to it. A range that lies completely outside [region], or that touches it only at its border, gives null, and so
     * does a range that covers only text the frame added between two lines of the piece.
     */
    public fun toPiece(range: TextRange): TextRange?
}

/** Frames a piece of text: puts it in its place in the whole text it belongs to. */
public fun interface TextFraming {
    /** The frame of [piece], or null if the piece has no place in the whole text, as when writing it takes it out. */
    public fun frame(piece: String): TextFrame?
}
