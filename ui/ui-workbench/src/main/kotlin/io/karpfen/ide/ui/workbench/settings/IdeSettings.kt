package io.karpfen.ide.ui.workbench.settings

import kotlinx.serialization.Serializable

/**
 * Persistent IDE settings (spec §8), stored as JSON by the [SettingsRepository]. Unknown JSON keys are ignored and
 * missing keys take the defaults, so older and newer files load.
 *
 * @property recentFolders absolute paths of recently opened folders, most recent first, at most [MAX_RECENT_FOLDERS].
 * @property theme light, dark or following the system.
 * @property editorFontSize editor font size in sp.
 * @property windowBounds the main window's last bounds, or null for the default placement.
 * @property toolWindows visibility and sizes of the tool windows.
 */
@Serializable
public data class IdeSettings(
    public val recentFolders: List<String> = emptyList(),
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val editorFontSize: Float = 13f,
    public val windowBounds: WindowBounds? = null,
    public val toolWindows: ToolWindowSettings = ToolWindowSettings(),
) {
    init {
        require(recentFolders.size <= MAX_RECENT_FOLDERS) { "at most $MAX_RECENT_FOLDERS recent folders" }
        require(editorFontSize > 0f) { "editorFontSize must be positive" }
    }

    /** These settings with [folder] as the most recent folder (moved to the front, list capped). */
    public fun withRecentFolder(folder: String): IdeSettings =
        copy(recentFolders = (listOf(folder) + recentFolders.filter { it != folder }).take(MAX_RECENT_FOLDERS))

    public companion object {
        /** Maximum number of recent folders (spec §8). */
        public const val MAX_RECENT_FOLDERS: Int = 10
    }
}

/** Theme selection. */
@Serializable
public enum class ThemeMode {
    /** Always light. */
    LIGHT,

    /** Always dark. */
    DARK,

    /** Follow the operating system. */
    SYSTEM,
}

/** Window placement in screen pixels; null position means "let the system place it". */
@Serializable
public data class WindowBounds(
    public val x: Int? = null,
    public val y: Int? = null,
    public val width: Int,
    public val height: Int,
    public val maximized: Boolean = false,
)

/** Tool-window layout: visibility and sizes in dp. */
@Serializable
public data class ToolWindowSettings(
    public val projectVisible: Boolean = true,
    public val outlineVisible: Boolean = true,
    public val problemsVisible: Boolean = true,
    public val projectWidth: Float = 260f,
    public val outlineWidth: Float = 240f,
    public val problemsHeight: Float = 200f,
)
