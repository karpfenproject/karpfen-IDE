package io.karpfen.ide.core.lang.environment

/**
 * Text that descriptor completion writes, with the place the caret goes to after writing it.
 *
 * @property text the text.
 * @property caret the caret's offset in [text]: the first place to fill, or the end of the text.
 * @throws IllegalArgumentException if [caret] lies outside [text].
 */
internal data class Template(val text: String, val caret: Int = text.length) {
    init {
        require(caret in 0..text.length) { "caret $caret must lie within the text (length ${text.length})" }
    }
}
