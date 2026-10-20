package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import io.karpfen.ide.ui.foundation.keys.compose.toKeyStroke

/** What a key stroke means inside a popup that owns focus (find bar, go-to-line). */
internal enum class PopupKey {
    /** Enter: find next, go to the line, replace. */
    CONFIRM,

    /** Shift+Enter: find previous. */
    CONFIRM_BACKWARD,

    /** Escape: close the popup and return focus to the text area. */
    CANCEL,
}

/**
 * The key policy of focused popups (pure): the strokes Enter, Shift+Enter and Escape without other modifiers; every
 * other stroke is left to the focused text field or to menu accelerators.
 */
internal object PopupKeys {

    private val bindings: Map<KeyStroke, PopupKey> = mapOf(
        KeyStroke(KeyCode.ENTER) to PopupKey.CONFIRM,
        KeyStroke(KeyCode.ENTER, setOf(KeyModifier.SHIFT)) to PopupKey.CONFIRM_BACKWARD,
        KeyStroke(KeyCode.ESCAPE) to PopupKey.CANCEL,
    )

    /** The popup key of [stroke], or null (also for null, i.e. key-up events and unmapped keys). */
    fun of(stroke: KeyStroke?): PopupKey? = stroke?.let(bindings::get)
}

/**
 * Offers the popup keys of key-down events reaching this element to [onKey] in the preview phase (before a text field
 * inside handles them) and consumes the event when [onKey] returns true. Strokes come from the shared key adapter
 * (`toKeyStroke`), like the editor's own key handling.
 */
internal fun Modifier.onPopupKey(onKey: (PopupKey) -> Boolean): Modifier =
    onPreviewKeyEvent { event -> PopupKeys.of(event.toKeyStroke())?.let(onKey) ?: false }
