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
package io.karpfen.ide.ui.workbench.settings

import kotlinx.serialization.Serializable

/**
 * Persistent IDE settings, stored as JSON by the [SettingsRepository]. Unknown JSON keys are ignored and missing keys
 * take the defaults, so older and newer files load.
 *
 * @property recentFolders absolute paths of recently opened folders, most recent first, at most [MAX_RECENT_FOLDERS].
 * @property theme light, dark or following the system.
 * @property editorFontSize editor font size in sp.
 * @property windowBounds the main window's last bounds, or null for the default placement.
 * @property toolWindows visibility and size of the tool windows by tool-window name (`ToolWindowId.name` of the
 *   workbench's layout package); a missing entry means the default layout, an unknown one is kept but ignored, so a
 *   new tool window needs no schema change.
 */
@Serializable
public data class IdeSettings(
    public val recentFolders: List<String> = emptyList(),
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val editorFontSize: Float = 13f,
    public val windowBounds: WindowBounds? = null,
    public val toolWindows: Map<String, ToolWindowLayout> = emptyMap(),
) {
    init {
        require(recentFolders.size <= MAX_RECENT_FOLDERS) { "at most $MAX_RECENT_FOLDERS recent folders" }
        require(editorFontSize > 0f) { "editorFontSize must be positive" }
    }

    /** These settings with [folder] as the most recent folder (moved to the front, list capped). */
    public fun withRecentFolder(folder: String): IdeSettings =
        copy(recentFolders = (listOf(folder) + recentFolders.filter { it != folder }).take(MAX_RECENT_FOLDERS))

    /** These settings without [folder] among the recent folders (e.g. it no longer exists). */
    public fun withoutRecentFolder(folder: String): IdeSettings = copy(recentFolders = recentFolders - folder)

    public companion object {
        /** Maximum number of recent folders. */
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

/** The persisted layout of one tool window: whether it is [visible] and its [size] in dp (width or height). */
@Serializable
public data class ToolWindowLayout(public val visible: Boolean = true, public val size: Float) {
    init {
        require(size > 0f) { "size must be positive, was $size" }
    }
}
