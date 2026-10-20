package io.karpfen.ide.ui.editor.view.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import io.karpfen.ide.ui.foundation.keys.compose.toKeyStroke

/**
 * The editor's keyboard input (spec §7.2): a focused, visually hidden proxy `BasicTextField` placed at the caret.
 *
 * Chosen approach (documented as the spec asks): Compose's platform text-input API on desktop
 * (`PlatformTextInputModifierNode` / `establishTextInputSession`) requires implementing the desktop
 * `PlatformTextInputMethodRequest`, whose members are all `@ExperimentalComposeUiApi` in this Compose version and which
 * carries two parallel editing models (`EditCommand`s and `TextEditingScope`), while typed characters still arrive as
 * AWT key-typed events the component must handle itself. The proxy field instead uses Compose's own, maintained text
 * input — key-typed characters, macOS Option combinations (German layout: Option+8 = `{`, Option+L = `@`), dead keys and
 * input methods with their pre-edit — exactly like every other text field of the IDE, and the editor only receives the
 * committed text:
 * - Key-downs go to [onKeyStroke] first (`CodeEditorState.handleKey`: completion, then the keymap) and the editor
 *   consumes exactly those for which it returns true (decision 15 of the work-package plan); unhandled strokes continue
 *   to the field and the window (menu accelerators). Limitation: the field itself still consumes the few text-editing
 *   strokes it knows (undo, clipboard, caret keys) when the editor's action for them did nothing — its text is always
 *   empty and its undo history cleared, so this has no effect, and the matching menu commands are disabled in that
 *   state anyway; shortcuts the field does not know (Save, Close, …) reach the menu. While the input method composes,
 *   keys belong to it and are not routed.
 * - A key-typed event following a handled key-down is swallowed ([TypedKeyGate]).
 * - Committed text is moved out of the field synchronously ([CommittedTextForwarder]) and passed to [onTyped]
 *   (`CodeEditorState.type`): when the field changes, and before every routed key so typing and key commands keep their
 *   order. Composing text stays in the field, drawn at the caret in the editor's style on its [background].
 *
 * Tests type through the `SemanticsActions.InsertTextAtCursor` action of `CodeEditor` (`performTextInput`); this field
 * itself is hidden from semantics.
 */
@Composable
internal fun ProxyTextInput(
    onTyped: (String) -> Unit,
    onKeyStroke: (KeyStroke) -> Boolean,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    textStyle: TextStyle,
    background: Color,
    modifier: Modifier = Modifier,
) {
    val field = rememberTextFieldState()
    val currentOnTyped by rememberUpdatedState(onTyped)
    val currentOnKeyStroke by rememberUpdatedState(onKeyStroke)
    val currentOnFocusChanged by rememberUpdatedState(onFocusChanged)
    val forwarder = remember(field) { CommittedTextForwarder(field) { currentOnTyped(it) } }
    val gate = remember { TypedKeyGate() }

    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() to field.composition }.collect { forwarder.flush() }
    }

    BasicTextField(
        state = field,
        modifier = modifier
            .onPreviewKeyEvent { event ->
                forwarder.flush()
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        val handled = field.composition == null &&
                            event.toKeyStroke()?.let { currentOnKeyStroke(it) } == true
                        gate.keyDown(handled)
                        handled
                    }
                    KeyEventType.KeyUp -> false
                    else -> gate.typed()
                }
            }
            .focusRequester(focusRequester)
            .onFocusChanged { currentOnFocusChanged(it.isFocused) }
            .clearAndSetSemantics { },
        textStyle = textStyle,
        cursorBrush = SolidColor(Color.Transparent),
        lineLimits = TextFieldLineLimits.SingleLine,
        decorator = { innerTextField ->
            val composing = field.composition != null
            Box(if (composing) Modifier.background(background) else Modifier) { innerTextField() }
        },
    )
}
