package io.karpfen.ide.ui.foundation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** The IDE colour palette (spec §6), provided through [LocalKarpfenColors]. */
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

/** UI and monospace text styles (spec §6), provided through [LocalKarpfenTypography]. */
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
public fun lightKarpfenColors(): KarpfenColors = TODO("WP-FOUNDATION: light palette")

/** The dark palette. */
public fun darkKarpfenColors(): KarpfenColors = TODO("WP-FOUNDATION: dark palette")

/** Default typography (platform UI font, a monospace font for code). */
public fun defaultKarpfenTypography(): KarpfenTypography = TODO("WP-FOUNDATION: typography")

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
 * Applies the karpfen theme (spec §6): provides [LocalKarpfenColors] (light or dark palette) and
 * [LocalKarpfenTypography], and configures the Material 3 theme used by basic widgets to match.
 */
@Composable
public fun KarpfenTheme(darkTheme: Boolean, content: @Composable () -> Unit): Unit =
    TODO("WP-FOUNDATION: provide palette, typography and Material theme")
