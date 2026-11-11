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
import androidx.compose.ui.focus.focusProperties
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
 * The keyboard input of the editor. It is a focused proxy `BasicTextField` that is placed at the caret and not visible.
 *
 * This approach was chosen over the platform text input API of Compose on desktop, which is
 * `PlatformTextInputModifierNode` with `establishTextInputSession`. That API requires an implementation of the desktop
 * `PlatformTextInputMethodRequest`. All its members are `@ExperimentalComposeUiApi` in this Compose version, and it
 * carries two parallel editing models, `EditCommand`s and `TextEditingScope`. Typed characters would still arrive as AWT
 * key-typed events that the component must handle itself. The proxy field instead uses the maintained text input of
 * Compose, exactly like every other text field of the IDE. That covers key-typed characters, the Option combinations of
 * macOS, dead keys, and input methods with their pre-edit text. On a German layout, for example, Option+8 types `{` and
 * Option+L types `@`. The editor only receives the committed text.
 *
 * Key-downs go to [onKeyStroke] first, which is `CodeEditorState.handleKey`. There completion gets the key first, then
 * the keymap. The editor consumes exactly the strokes for which it returns true. Unhandled strokes continue to the field
 * and to the window, where the menu accelerators live. One limitation remains. The field still consumes the few text
 * editing strokes it knows, such as undo, clipboard and caret keys, when the editor's action for them did nothing. This
 * has no effect, because the text of the field is always empty and its undo history is cleared. The matching menu
 * commands are disabled in that state anyway. Shortcuts the field does not know, such as Save and Close, reach the
 * menu. While the input method composes, keys belong to it and are not routed.
 *
 * Focus never leaves the editor by focus traversal ([withoutFocusTraversal]). Tab and Shift+Tab are text keys of the
 * editor for indent and outdent, also when they change nothing and stay unhandled.
 *
 * A key-typed event that follows a handled key-down is swallowed by [TypedKeyGate].
 *
 * Committed text is moved out of the field synchronously by [CommittedTextForwarder] and passed to [onTyped], which is
 * `CodeEditorState.type`. That happens when the field changes and before every routed key, so typing and key commands
 * keep their order. Composing text stays in the field and is drawn at the caret in the editor's style on its
 * [background].
 *
 * Tests type through the `SemanticsActions.InsertTextAtCursor` action of `CodeEditor` with `performTextInput`. The
 * field itself is hidden from semantics.
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
            .withoutFocusTraversal()
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

/**
 * Cancels focus traversal out of the field. Tab and Shift+Tab are the indent keys of the editor. So a stroke that the
 * editor leaves unhandled, because there is nothing to indent or outdent, must not move keyboard focus to a
 * neighbouring component.
 */
private fun Modifier.withoutFocusTraversal(): Modifier = focusProperties {
    next = FocusRequester.Cancel
    previous = FocusRequester.Cancel
}
