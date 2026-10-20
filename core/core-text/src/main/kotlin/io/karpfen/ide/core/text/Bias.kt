package io.karpfen.ide.core.text

/**
 * How offsets are mapped across an edit when they sit exactly at an insertion point or inside a replaced range.
 *
 * - At an insertion offset: [LEFT] stays before the inserted text, [RIGHT] moves behind it.
 * - Inside a replaced or deleted range: [LEFT] maps to the start of the replacement, [RIGHT] to its end.
 * - Offsets before or after every edit are shifted unambiguously, independent of the bias.
 */
public enum class Bias {
    /** Stick to the text before the edit point. */
    LEFT,

    /** Stick to the text after the edit point (default: carets move along with typed text). */
    RIGHT,
}
