package io.karpfen.ide.ui.editor.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.karpfen.ide.core.lang.HighlightStyle

/**
 * The two editor palettes (immutable constants), matching the window colours of the karpfen theme in ui-foundation:
 * neutral backgrounds, blue selection, the foundation's severity colours. Syntax colours map every [HighlightStyle]:
 * karpfen keywords bold, action operations in their own colour, names by semantic kind (types, states, properties,
 * objects, macros, events), embedded Python in muted variants of the same hues, comments italic, and file paths in
 * environment descriptors underlined like links. Text colours keep a contrast of at least 4.5:1 on the editor
 * background (tested).
 */
internal object EditorPalettes {

    /** The light palette, for the light karpfen theme. */
    val light: EditorTheme = EditorTheme(
        background = Color(0xFFFFFFFF),
        foreground = Color(0xFF1E1F22),
        gutterBackground = Color(0xFFF7F8FA),
        gutterForeground = Color(0xFF6E7380),
        currentLine = Color(0xFFF5F8FE),
        selection = Color(0xFFD5E1FF),
        caret = Color(0xFF1E1F22),
        matchingBracket = Color(0xFF8FA9D8),
        findMatch = Color(0xFFFFE9A8),
        findMatchCurrent = Color(0xFFFFC94D),
        error = Color(0xFFD12B3C),
        warning = Color(0xFFA86400),
        info = Color(0xFF2F6FD6),
        popupBackground = Color(0xFFFFFFFF),
        popupBorder = Color(0xFFDFE1E5),
        popupSelection = Color(0xFFD5E1FF),
        styles = styles(
            keyword = Color(0xFF0033B3),
            operation = Color(0xFF8C1AAB),
            string = Color(0xFF067D17),
            number = Color(0xFF1750EB),
            comment = Color(0xFF6E7380),
            punctuation = Color(0xFF3E4048),
            identifier = Color(0xFF1E1F22),
            typeName = Color(0xFF00627A),
            propertyName = Color(0xFF871094),
            objectName = Color(0xFF7A4B00),
            stateName = Color(0xFF0E6B5C),
            macroName = Color(0xFF9E3F00),
            eventName = Color(0xFFB0216F),
            modelPath = Color(0xFF3A5A9B),
            embedded = Color(0xFF2B4D6B),
            error = Color(0xFFD12B3C),
        ),
    )

    /** The dark palette, for the dark karpfen theme. */
    val dark: EditorTheme = EditorTheme(
        background = Color(0xFF1E1F22),
        foreground = Color(0xFFDFE1E5),
        gutterBackground = Color(0xFF2B2D30),
        gutterForeground = Color(0xFF8C909A),
        currentLine = Color(0xFF26282E),
        selection = Color(0xFF2A3B5E),
        caret = Color(0xFFDFE1E5),
        matchingBracket = Color(0xFF5C6D8E),
        findMatch = Color(0xFF4A4120),
        findMatchCurrent = Color(0xFF7A6421),
        error = Color(0xFFF75464),
        warning = Color(0xFFF2C55C),
        info = Color(0xFF6CA2F8),
        popupBackground = Color(0xFF2B2D30),
        popupBorder = Color(0xFF43454A),
        popupSelection = Color(0xFF2A3B5E),
        styles = styles(
            keyword = Color(0xFFCF8E6D),
            operation = Color(0xFFC77DBB),
            string = Color(0xFF6AAB73),
            number = Color(0xFF2AACB8),
            comment = Color(0xFF8C909A),
            punctuation = Color(0xFFBCBEC4),
            identifier = Color(0xFFDFE1E5),
            typeName = Color(0xFF56C1D6),
            propertyName = Color(0xFFC77DBB),
            objectName = Color(0xFFE0B45C),
            stateName = Color(0xFF6FC2A8),
            macroName = Color(0xFFE8A06A),
            eventName = Color(0xFFEE7FB4),
            modelPath = Color(0xFF8FAEE8),
            embedded = Color(0xFFB4C6D9),
            error = Color(0xFFF75464),
        ),
    )

    /** The style of every [HighlightStyle] from a handful of hues (one place per palette, no style left out). */
    @Suppress("LongParameterList")
    private fun styles(
        keyword: Color,
        operation: Color,
        string: Color,
        number: Color,
        comment: Color,
        punctuation: Color,
        identifier: Color,
        typeName: Color,
        propertyName: Color,
        objectName: Color,
        stateName: Color,
        macroName: Color,
        eventName: Color,
        modelPath: Color,
        embedded: Color,
        error: Color,
    ): Map<HighlightStyle, SpanStyle> = HighlightStyle.entries.associateWith { style ->
        when (style) {
            HighlightStyle.KEYWORD -> SpanStyle(color = keyword, fontWeight = FontWeight.Bold)
            HighlightStyle.OPERATION -> SpanStyle(color = operation, fontWeight = FontWeight.Bold)
            HighlightStyle.STRING -> SpanStyle(color = string)
            HighlightStyle.NUMBER -> SpanStyle(color = number)
            HighlightStyle.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
            HighlightStyle.PUNCTUATION, HighlightStyle.OPERATOR -> SpanStyle(color = punctuation)
            HighlightStyle.IDENTIFIER -> SpanStyle(color = identifier)
            HighlightStyle.TYPE_NAME -> SpanStyle(color = typeName)
            HighlightStyle.PRIMITIVE_TYPE -> SpanStyle(color = keyword)
            HighlightStyle.PROPERTY_NAME -> SpanStyle(color = propertyName)
            HighlightStyle.OBJECT_NAME -> SpanStyle(color = objectName)
            HighlightStyle.STATE_NAME -> SpanStyle(color = stateName)
            HighlightStyle.MACRO_NAME, HighlightStyle.MACRO_REFERENCE -> SpanStyle(color = macroName)
            HighlightStyle.EVENT_NAME, HighlightStyle.EVENT_DOMAIN -> SpanStyle(color = eventName)
            HighlightStyle.MODEL_PATH, HighlightStyle.PATH_REFERENCE -> SpanStyle(color = modelPath)
            HighlightStyle.DESCRIPTION -> SpanStyle(color = string, fontStyle = FontStyle.Italic)
            HighlightStyle.BINDING_NAME -> SpanStyle(color = objectName, fontStyle = FontStyle.Italic)
            HighlightStyle.FILE_PATH -> SpanStyle(color = modelPath, textDecoration = TextDecoration.Underline)
            HighlightStyle.EMBEDDED_CODE -> SpanStyle(color = embedded)
            HighlightStyle.EMBEDDED_KEYWORD -> SpanStyle(color = keyword)
            HighlightStyle.EMBEDDED_COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
            HighlightStyle.EMBEDDED_STRING -> SpanStyle(color = string)
            HighlightStyle.EMBEDDED_NUMBER -> SpanStyle(color = number)
            HighlightStyle.ERROR -> SpanStyle(color = error)
        }
    }
}
