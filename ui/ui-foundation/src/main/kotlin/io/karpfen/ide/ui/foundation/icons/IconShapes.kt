package io.karpfen.ide.ui.foundation.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import io.karpfen.ide.ui.foundation.icons.IconDrawing.TINTABLE
import io.karpfen.ide.ui.foundation.icons.IconDrawing.circle
import io.karpfen.ide.ui.foundation.icons.IconDrawing.filled
import io.karpfen.ide.ui.foundation.icons.IconDrawing.icon
import io.karpfen.ide.ui.foundation.icons.IconDrawing.polyline
import io.karpfen.ide.ui.foundation.icons.IconDrawing.roundedRect
import io.karpfen.ide.ui.foundation.icons.IconDrawing.stroked

/**
 * The shapes of [KarpfenIcons]. File and severity icons carry their own mid-tone colours (readable on the light and the
 * dark palette); glyph icons (close, chevrons, search) are drawn in [TINTABLE] black and meant to be tinted.
 */
internal object IconShapes {

    private val FOLDER = Color(0xFFE2AE48)
    private val FOLDER_TAB = Color(0xFFC08E2E)
    private val FILE = Color(0xFFAEB3BD)
    private val FILE_FOLD = Color(0xFF878D99)
    private val KMETA = Color(0xFF9C6ADE)
    private val KMODEL = Color(0xFF3D8CE0)
    private val KSTATES = Color(0xFF3AA169)
    private val JSON = Color(0xFFD9822B)
    private val ERROR = Color(0xFFE55765)
    private val WARNING = Color(0xFFF0A732)
    private val INFO = Color(0xFF4C8DE8)
    private val ON_COLOR = Color.White
    private val ON_WARNING = Color(0xFF2B2D30)

    fun folder(): ImageVector = icon("Folder") {
        filled(FOLDER_TAB) { polyline(1.5f, 4.5f, 1.5f, 3f, 6f, 3f, 7.5f, 4.5f); close() }
        filled(FOLDER) { roundedRect(1.5f, 4.5f, 14.5f, 13f, 1f) }
    }

    fun file(): ImageVector = icon("File") {
        filled(FILE) { polyline(3f, 1.5f, 9.5f, 1.5f, 13f, 5f, 13f, 14.5f, 3f, 14.5f); close() }
        filled(FILE_FOLD) { polyline(9.5f, 1.5f, 9.5f, 5f, 13f, 5f); close() }
    }

    /** KMeta: an "M" (metamodel) on a purple tile. */
    fun kmeta(): ImageVector = icon("Kmeta") {
        filled(KMETA) { tile() }
        stroked(ON_COLOR, width = 1.6f) { polyline(4.75f, 11.25f, 4.75f, 4.75f, 8f, 8.75f, 11.25f, 4.75f, 11.25f, 11.25f) }
    }

    /** KModel: a ring (an object instance) on a blue tile. */
    fun kmodel(): ImageVector = icon("Kmodel") {
        filled(KMODEL) { tile() }
        stroked(ON_COLOR, width = 1.6f) { circle(8f, 8f, 3.25f) }
    }

    /** KStates: an "S" (statechart) on a green tile. */
    fun kstates(): ImageVector = icon("Kstates") {
        filled(KSTATES) { tile() }
        stroked(ON_COLOR, width = 1.6f) {
            moveTo(10.9f, 5.3f)
            curveTo(10.3f, 4.5f, 9.3f, 4.2f, 8f, 4.2f)
            curveTo(6.4f, 4.2f, 5.2f, 5f, 5.2f, 6.1f)
            curveTo(5.2f, 8.5f, 10.8f, 7.4f, 10.8f, 9.9f)
            curveTo(10.8f, 11f, 9.6f, 11.8f, 8f, 11.8f)
            curveTo(6.6f, 11.8f, 5.6f, 11.4f, 5f, 10.6f)
        }
    }

    /** JSON: braces on an orange tile. */
    fun json(): ImageVector = icon("Json") {
        filled(JSON) { tile() }
        stroked(ON_COLOR, width = 1.4f) {
            moveTo(6.5f, 4f)
            curveTo(5.2f, 4f, 5.3f, 5f, 5.3f, 6.2f)
            curveTo(5.3f, 7.4f, 5f, 8f, 4.1f, 8f)
            curveTo(5f, 8f, 5.3f, 8.6f, 5.3f, 9.8f)
            curveTo(5.3f, 11f, 5.2f, 12f, 6.5f, 12f)
            moveTo(9.5f, 4f)
            curveTo(10.8f, 4f, 10.7f, 5f, 10.7f, 6.2f)
            curveTo(10.7f, 7.4f, 11f, 8f, 11.9f, 8f)
            curveTo(11f, 8f, 10.7f, 8.6f, 10.7f, 9.8f)
            curveTo(10.7f, 11f, 10.8f, 12f, 9.5f, 12f)
        }
    }

    /** Error: an exclamation mark in a red circle. */
    fun error(): ImageVector = icon("Error") {
        filled(ERROR) { circle(8f, 8f, 6.75f) }
        exclamationMark(ON_COLOR, top = 4f, bottom = 9f, dotY = 11.4f)
    }

    /** Warning: an exclamation mark in an amber triangle. */
    fun warning(): ImageVector = icon("Warning") {
        filled(WARNING) {
            polyline(8f, 1.25f, 15f, 14f, 1f, 14f)
            close()
        }
        exclamationMark(ON_WARNING, top = 5.5f, bottom = 9.6f, dotY = 11.9f)
    }

    /** Info: an "i" in a blue circle. */
    fun info(): ImageVector = icon("Info") {
        filled(INFO) { circle(8f, 8f, 6.75f) }
        filled(ON_COLOR) { circle(8f, 4.9f, 1f) }
        stroked(ON_COLOR, width = 1.6f) { polyline(8f, 7.4f, 8f, 11.6f) }
    }

    fun close(): ImageVector = icon("Close") {
        stroked(TINTABLE) {
            polyline(4.5f, 4.5f, 11.5f, 11.5f)
            polyline(11.5f, 4.5f, 4.5f, 11.5f)
        }
    }

    fun chevronRight(): ImageVector = icon("ChevronRight") {
        stroked(TINTABLE) { polyline(6f, 4f, 10f, 8f, 6f, 12f) }
    }

    fun chevronDown(): ImageVector = icon("ChevronDown") {
        stroked(TINTABLE) { polyline(4f, 6f, 8f, 10f, 12f, 6f) }
    }

    fun search(): ImageVector = icon("Search") {
        stroked(TINTABLE) { circle(7f, 7f, 4.25f) }
        stroked(TINTABLE, width = 1.75f) { polyline(10.2f, 10.2f, 13.75f, 13.75f) }
    }

    /** The rounded tile behind the file-type glyphs. */
    private fun PathBuilder.tile() = roundedRect(1.5f, 1.5f, 14.5f, 14.5f, 3f)

    private fun ImageVector.Builder.exclamationMark(color: Color, top: Float, bottom: Float, dotY: Float) {
        stroked(color, width = 1.7f) { polyline(8f, top, 8f, bottom) }
        filled(color) { circle(8f, dotY, 1f) }
    }
}
