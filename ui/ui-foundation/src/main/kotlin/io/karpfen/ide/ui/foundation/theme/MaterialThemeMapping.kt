package io.karpfen.ide.ui.foundation.theme

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Maps the karpfen theme onto the Material 3 theme and the desktop scrollbars, so basic Material widgets (menus,
 * buttons, text fields, dialogs) match the IDE palette: accent → primary, window → background, panel → surface,
 * border → outline. Menus and dialogs use the window colour in the light theme and the panel colour in the dark one.
 */
internal object MaterialThemeMapping {

    fun colorScheme(colors: KarpfenColors, darkTheme: Boolean): ColorScheme {
        val container = if (darkTheme) colors.panel else colors.window
        val base = if (darkTheme) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = colors.accent,
            onPrimary = Color.White,
            primaryContainer = colors.selection,
            onPrimaryContainer = colors.text,
            secondary = colors.accent,
            onSecondary = Color.White,
            secondaryContainer = colors.selection,
            onSecondaryContainer = colors.text,
            tertiary = colors.info,
            onTertiary = Color.White,
            background = colors.window,
            onBackground = colors.text,
            surface = colors.panel,
            onSurface = colors.text,
            surfaceVariant = colors.panel,
            onSurfaceVariant = colors.textMuted,
            surfaceTint = colors.panel,
            surfaceBright = container,
            surfaceDim = colors.panel,
            surfaceContainerLowest = container,
            surfaceContainerLow = container,
            surfaceContainer = container,
            surfaceContainerHigh = container,
            surfaceContainerHighest = container,
            inverseSurface = colors.text,
            inverseOnSurface = colors.window,
            inversePrimary = colors.selection,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.error,
            onError = Color.White,
            errorContainer = colors.error.copy(alpha = 0.15f),
            onErrorContainer = colors.text,
            scrim = Color.Black.copy(alpha = 0.32f),
        )
    }

    fun typography(typography: KarpfenTypography): Typography {
        val title = typography.ui.copy(fontWeight = FontWeight.Medium)
        return Typography(
            titleLarge = title.copy(fontSize = 18.sp, lineHeight = 24.sp),
            titleMedium = title.copy(fontSize = 15.sp, lineHeight = 20.sp),
            titleSmall = title,
            bodyLarge = typography.ui,
            bodyMedium = typography.ui,
            bodySmall = typography.uiSmall,
            labelLarge = typography.ui,
            labelMedium = typography.uiSmall,
            labelSmall = typography.uiSmall,
        )
    }

    fun scrollbarStyle(colors: KarpfenColors): ScrollbarStyle = ScrollbarStyle(
        minimalHeight = 16.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300,
        unhoverColor = colors.text.copy(alpha = 0.22f),
        hoverColor = colors.text.copy(alpha = 0.45f),
    )
}
