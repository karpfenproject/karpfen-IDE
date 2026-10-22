package io.karpfen.ide.ui.foundation.keys

/**
 * The layout-aware punctuation rule of the key adapter, Compose-free so it is unit-testable without key events: a key
 * press that produced one of the punctuation characters of [KeyCode] is reported as that character's code; Shift is
 * dropped when the layout needed it for the character, i.e. when the physical key is not the character's own key
 * (German Shift+7 produces `/` → [KeyCode.SLASH] without Shift). Every other press is reported as its physical key with
 * its modifiers (US Shift+/ produces `?` → SLASH + Shift).
 */
internal object LayoutAwareStrokes {

    /**
     * The stroke of a key press: [physical] is the code of the pressed key (null for keys without one, e.g. a lone
     * modifier), [modifiers] the physical modifiers held, [produced] the character the press produced on the current
     * layout, if known.
     */
    fun resolve(physical: KeyCode?, modifiers: Set<KeyModifier>, produced: Char?): KeyStroke? {
        val punctuation = produced?.let(PunctuationKeys::keyOf)
        return when {
            punctuation == null -> physical?.let { KeyStroke(it, modifiers) }
            punctuation == physical -> KeyStroke(punctuation, modifiers)
            else -> KeyStroke(punctuation, modifiers - KeyModifier.SHIFT)
        }
    }

    /** Whether [char] is a character a key press can produce as text (no control character, not "undefined"). */
    fun isPrintable(char: Char): Boolean =
        char >= ' ' && char != DELETE_CHAR && char != UNDEFINED_CHAR && !char.isSurrogate()

    private const val DELETE_CHAR = '\u007f'

    /** AWT's `KeyEvent.CHAR_UNDEFINED`. */
    private const val UNDEFINED_CHAR = '￿'
}
