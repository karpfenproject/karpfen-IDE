package io.karpfen.ide.ui.foundation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The palettes and the typography of the karpfen theme: immutable constants (neutral greys with a blue accent, in the
 * spirit of current desktop IDEs). Text colours keep a WCAG contrast of at least 4.5:1 on the window, panel and
 * selection backgrounds, severity colours at least 3:1 on the window background (tested).
 */
internal object KarpfenPalettes {

    val light: KarpfenColors = KarpfenColors(
        window = Color(0xFFFFFFFF),
        panel = Color(0xFFF7F8FA),
        border = Color(0xFFDFE1E5),
        text = Color(0xFF1E1F22),
        textMuted = Color(0xFF5E6270),
        accent = Color(0xFF3574F0),
        selection = Color(0xFFD5E1FF),
        focus = Color(0xFF3574F0),
        error = Color(0xFFD12B3C),
        warning = Color(0xFFA86400),
        info = Color(0xFF2F6FD6),
    )

    val dark: KarpfenColors = KarpfenColors(
        window = Color(0xFF1E1F22),
        panel = Color(0xFF2B2D30),
        border = Color(0xFF43454A),
        text = Color(0xFFDFE1E5),
        textMuted = Color(0xFFA8ABB2),
        accent = Color(0xFF548AF7),
        selection = Color(0xFF2A3B5E),
        focus = Color(0xFF548AF7),
        error = Color(0xFFF75464),
        warning = Color(0xFFF2C55C),
        info = Color(0xFF6CA2F8),
    )

    /**
     * UI text in the platform's default (sans-serif) font, code in the platform's monospace font: no bundled font
     * resources, so the IDE looks native on macOS, Windows and Linux.
     */
    val typography: KarpfenTypography = KarpfenTypography(
        ui = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        uiSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        monospace = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    )
}
