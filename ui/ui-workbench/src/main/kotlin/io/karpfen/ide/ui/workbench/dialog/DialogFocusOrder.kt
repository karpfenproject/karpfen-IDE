package io.karpfen.ide.ui.workbench.dialog

/**
 * The keyboard focus order of a modal dialog (pure): its focus stops are the text field — or the dialog itself when it
 * has none — followed by the buttons, of which only the [focusable] ones (enabled) take the focus. Tab ([next]) and
 * Shift+Tab ([previous]) cycle through them and never leave the dialog.
 *
 * @param focusable per stop whether it can take the focus; the first stop always can.
 */
internal class DialogFocusOrder(private val focusable: List<Boolean>) {

    init {
        require(focusable.firstOrNull() == true) { "the first focus stop must take the focus" }
    }

    /** The number of focus stops. */
    val size: Int get() = focusable.size

    /** The stop Tab moves to from [stop]. */
    fun next(stop: Int): Int = step(stop, 1)

    /** The stop Shift+Tab moves to from [stop]. */
    fun previous(stop: Int): Int = step(stop, -1)

    /** The first focusable stop after [stop] in [direction], wrapping around (the first stop always qualifies). */
    private fun step(stop: Int, direction: Int): Int =
        (1..size).map { Math.floorMod(stop + direction * it, size) }.first { focusable[it] }

    companion object {
        /** The focus order of [presentation] with [text] entered (a button whose answer is not possible is disabled). */
        fun of(presentation: DialogPresentation, text: String): DialogFocusOrder =
            DialogFocusOrder(listOf(true) + presentation.buttons.map { presentation.responseFor(it.choice, text) != null })
    }
}
