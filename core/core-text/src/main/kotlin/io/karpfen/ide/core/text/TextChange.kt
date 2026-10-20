package io.karpfen.ide.core.text

/**
 * One applied change: [before] became [after] by [edits].
 *
 * Invariants: `after.version == before.version + 1`; [edits] are non-empty, non-overlapping, sorted by range start
 * (insertions at the same offset keep their original relative order) and expressed in [before] coordinates.
 */
public data class TextChange(
    public val before: TextSnapshot,
    public val after: TextSnapshot,
    public val edits: List<TextEdit>,
    public val origin: EditOrigin,
) {
    init {
        require(after.version == before.version + 1) {
            "a change advances the version by exactly one (${before.version} -> ${after.version})"
        }
        require(edits.isNotEmpty()) { "a change has at least one edit" }
    }

    /** The version this change produced. */
    public val version: Long get() = after.version
}
