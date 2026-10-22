package io.karpfen.ide.ui.foundation.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** The IDE colour palette, provided through [LocalKarpfenColors]. */
@Immutable
public data class KarpfenColors(
    /** Window background. */
    public val window: Color,
    /** Tool-window and panel background. */
    public val panel: Color,
    /** Separators and borders. */
    public val border: Color,
    /** Primary text. */
    public val text: Color,
    /** Secondary text (details, disabled). */
    public val textMuted: Color,
    /** Accent (active tab marker, links, primary buttons). */
    public val accent: Color,
    /** Selected list/tree rows. */
    public val selection: Color,
    /** Keyboard focus indication. */
    public val focus: Color,
    /** Errors. */
    public val error: Color,
    /** Warnings. */
    public val warning: Color,
    /** Information. */
    public val info: Color,
)

/** UI and monospace text styles, provided through [LocalKarpfenTypography]. */
@Immutable
public data class KarpfenTypography(
    /** Default UI text. */
    public val ui: TextStyle,
    /** Small UI text (status bar, badges). */
    public val uiSmall: TextStyle,
    /** Monospace text (editor, problems positions). */
    public val monospace: TextStyle,
)

/** The light palette. */
public fun lightKarpfenColors(): KarpfenColors = KarpfenPalettes.light

/** The dark palette. */
public fun darkKarpfenColors(): KarpfenColors = KarpfenPalettes.dark

/** Default typography (platform UI font, a monospace font for code). */
public fun defaultKarpfenTypography(): KarpfenTypography = KarpfenPalettes.typography

/** The current palette; provided by [KarpfenTheme]. */
public val LocalKarpfenColors: ProvidableCompositionLocal<KarpfenColors> =
    staticCompositionLocalOf { error("No KarpfenColors provided; wrap the content in KarpfenTheme") }

/** The current typography; provided by [KarpfenTheme]. */
public val LocalKarpfenTypography: ProvidableCompositionLocal<KarpfenTypography> =
    staticCompositionLocalOf { error("No KarpfenTypography provided; wrap the content in KarpfenTheme") }

/** Accessors for the current theme values inside [KarpfenTheme] content. */
public object KarpfenTheme {
    /** The current palette. */
    public val colors: KarpfenColors
        @Composable @ReadOnlyComposable
        get() = LocalKarpfenColors.current

    /** The current typography. */
    public val typography: KarpfenTypography
        @Composable @ReadOnlyComposable
        get() = LocalKarpfenTypography.current
}

/**
 * Applies the karpfen theme: provides [LocalKarpfenColors] (light or dark palette) and
 * [LocalKarpfenTypography], and configures the Material 3 theme used by basic widgets to match. Inside [content],
 * Material's content colour is the palette's text colour, its default text style the UI style, and the desktop
 * scrollbars are styled from the palette.
 */
@Composable
public fun KarpfenTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) darkKarpfenColors() else lightKarpfenColors()
    val typography = defaultKarpfenTypography()
    val colorScheme = remember(colors, darkTheme) { MaterialThemeMapping.colorScheme(colors, darkTheme) }
    val materialTypography = remember(typography) { MaterialThemeMapping.typography(typography) }
    val scrollbarStyle = remember(colors) { MaterialThemeMapping.scrollbarStyle(colors) }
    CompositionLocalProvider(LocalKarpfenColors provides colors, LocalKarpfenTypography provides typography) {
        MaterialTheme(colorScheme = colorScheme, typography = materialTypography) {
            CompositionLocalProvider(
                LocalContentColor provides colors.text,
                LocalTextStyle provides typography.ui,
                LocalScrollbarStyle provides scrollbarStyle,
                content = content,
            )
        }
    }
}
