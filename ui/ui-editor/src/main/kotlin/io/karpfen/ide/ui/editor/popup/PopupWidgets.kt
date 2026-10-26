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

/** Shared building blocks of the popups, all coloured by the [EditorTheme] the host passes. */
internal object PopupStyle {
    /** Corner shape of popup surfaces, fields and buttons. */
    val shape = RoundedCornerShape(4.dp)

    /** Alpha of secondary text (details, hints) and of disabled entries. */
    const val MUTED_ALPHA = 0.6f

    /** Alpha of disabled controls (Material's disabled content alpha). */
    const val DISABLED_ALPHA = 0.38f
}

/** The UI text style of popup labels (the ambient UI font, e.g. the workbench theme's) in [theme]'s foreground. */
@Composable
internal fun popupLabelStyle(theme: EditorTheme): TextStyle = LocalTextStyle.current.merge(TextStyle(color = theme.foreground))

/** A popup's surface: popup background, 1 dp popup border, small corners and a shadow, around [content]. */
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
 * A single-line text field of a popup: [value] in [textStyle] with [theme]'s foreground and caret colours, a border, and
 * [placeholder] in muted colour while empty. [modifier] applies to the field itself (focus requester, key handling).
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
 * A compact button labelled [label] (e.g. an arrow glyph), described by [description] for accessibility and tests.
 * Never takes keyboard focus, so a click keeps the caret in the popup's text field.
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
 * A compact on/off toggle labelled [label] (e.g. `Aa`), described by [description]; highlighted while [checked]. Never
 * takes keyboard focus.
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
