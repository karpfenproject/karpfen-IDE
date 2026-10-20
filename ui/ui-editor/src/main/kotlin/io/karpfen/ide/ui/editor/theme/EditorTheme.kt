package io.karpfen.ide.ui.editor.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import io.karpfen.ide.core.lang.HighlightStyle

/**
 * Colours of the code editor and its popups (spec §7.2). [styles] maps every [HighlightStyle] to a span style
 * (colour, font weight/style); a missing style renders with [foreground].
 */
@Immutable
public data class EditorTheme(
    public val background: Color,
    public val foreground: Color,
    public val gutterBackground: Color,
    public val gutterForeground: Color,
    public val currentLine: Color,
    public val selection: Color,
    public val caret: Color,
    public val matchingBracket: Color,
    public val findMatch: Color,
    public val findMatchCurrent: Color,
    public val error: Color,
    public val warning: Color,
    public val info: Color,
    public val popupBackground: Color,
    public val popupBorder: Color,
    public val popupSelection: Color,
    public val styles: Map<HighlightStyle, SpanStyle>,
)

/** The light editor theme. */
public fun lightEditorTheme(): EditorTheme = EditorPalettes.light

/** The dark editor theme. */
public fun darkEditorTheme(): EditorTheme = EditorPalettes.dark

/** The editor theme for the current composition; the workbench provides light or dark. */
public val LocalEditorTheme: ProvidableCompositionLocal<EditorTheme> = staticCompositionLocalOf { lightEditorTheme() }
