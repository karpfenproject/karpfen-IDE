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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.editor.theme.EditorTheme

/** Shared style values of the popups. The popup widgets take their colours from the [EditorTheme] the host passes. */
internal object PopupStyle {
    /** The corner shape of popup surfaces, fields and buttons. */
    val shape = RoundedCornerShape(4.dp)

    /** The alpha of secondary text, such as details, hints and placeholders. */
    const val MUTED_ALPHA = 0.6f

    /** The alpha of disabled controls and menu entries. It is the disabled content alpha of Material. */
    const val DISABLED_ALPHA = 0.38f
}

/**
 * Returns the text style of popup labels. It uses the ambient UI font, such as the one of the workbench theme, in the
 * foreground colour of [theme].
 */
@Composable
internal fun popupLabelStyle(theme: EditorTheme): TextStyle = LocalTextStyle.current.merge(TextStyle(color = theme.foreground))

/** The surface of a popup around [content]. It has the popup background, a 1 dp popup border, small corners and a shadow. */
@Composable
internal fun PopupSurface(theme: EditorTheme, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .shadow(4.dp, PopupStyle.shape)
            .background(theme.popupBackground, PopupStyle.shape)
            .border(1.dp, theme.popupBorder, PopupStyle.shape),
        content = content,
    )
}

/**
 * A single-line text field of a popup. It shows [value] in [textStyle] with the foreground and caret colours of [theme]
 * and a border. While it is empty, it shows [placeholder] in a muted colour. [modifier] applies to the field itself,
 * for example for a focus requester and key handling.
 */
@Composable
internal fun PopupTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textStyle: TextStyle,
    theme: EditorTheme,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = textStyle.merge(TextStyle(color = theme.foreground)),
        cursorBrush = SolidColor(theme.caret),
        decorationBox = { field ->
            Box(
                Modifier
                    .background(theme.background, PopupStyle.shape)
                    .border(1.dp, theme.popupBorder, PopupStyle.shape)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                if (value.text.isEmpty()) {
                    BasicText(placeholder, style = textStyle.merge(TextStyle(color = theme.foreground.copy(alpha = PopupStyle.MUTED_ALPHA))))
                }
                field()
            }
        },
    )
}

/**
 * A compact button labelled [label], for example with an arrow glyph. [description] describes it for accessibility and
 * tests. It never takes keyboard focus, so a click keeps the caret in the text field of the popup.
 */
@Composable
internal fun BarButton(
    label: String,
    description: String,
    theme: EditorTheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (enabled) theme.foreground else theme.foreground.copy(alpha = PopupStyle.DISABLED_ALPHA)
    Box(
        modifier
            .clip(PopupStyle.shape)
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BasicText(label, style = popupLabelStyle(theme).merge(TextStyle(color = color)), maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/**
 * A compact on and off toggle labelled [label], for example `Aa`. [description] describes it. It is highlighted while
 * [checked] is true. It never takes keyboard focus.
 */
@Composable
internal fun BarToggle(label: String, description: String, checked: Boolean, theme: EditorTheme, onToggle: () -> Unit) {
    val background = if (checked) theme.popupSelection else Color.Transparent
    val border = if (checked) theme.popupBorder else Color.Transparent
    Box(
        Modifier
            .clip(PopupStyle.shape)
            .background(background, PopupStyle.shape)
            .border(1.dp, border, PopupStyle.shape)
            .focusProperties { canFocus = false }
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description }
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        BasicText(label, style = popupLabelStyle(theme), maxLines = 1)
    }
}
