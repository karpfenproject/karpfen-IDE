package io.karpfen.ide.core.text

/**
 * The identity of one line of text versions: every [TextSnapshot] of one [EditableText] carries the same lineage, and
 * a snapshot created on its own (a file loaded from disk, a test value) has a lineage of its own.
 *
 * Versions count per lineage (each [EditableText] starts at 0, a document closed and reopened starts again at 0), so a
 * version alone does not say which text it belongs to. Whoever maps a versioned range into a text
 * ([EditableText.mapOffset]) first checks that the range's lineage is the text's [EditableText.lineage]; a range of
 * another lineage is clamped instead of mapped through an unrelated edit history.
 *
 * Equality is identity; no global counter or other shared state is involved.
 */
public class TextLineage {
    override fun toString(): String = "TextLineage@${Integer.toHexString(System.identityHashCode(this))}"
}
