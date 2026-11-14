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

import io.karpfen.dsl.states.KstatesFeature
import kotlinx.serialization.Serializable

/**
 * The settings the IDE works with. They come from two places, and the [SettingsRepository] keeps them apart.
 *
 * [recentFolders], [windowBounds] and [runtimeWindowBounds] belong to the user and the machine. They are stored as JSON in the IDE's home
 * directory. Everything else belongs to the open project and is stored in the file `.karpfenproject` in the project
 * folder, as [ProjectSettings] describes. While no folder is open, the project part holds its defaults and is not
 * stored anywhere.
 *
 * @property recentFolders the absolute paths of recently opened folders, most recent first. There are at most
 *   [MAX_RECENT_FOLDERS] of them.
 * @property theme light, dark, or following the system. Part of the project.
 * @property editorFontSize the editor font size in sp. The editor's zoom buttons in the status bar change it. Part of
 *   the project.
 * @property uiScale the factor by which the whole workbench is drawn larger or smaller, 1 for the normal size. The
 *   status bar's zoom buttons change it. Part of the project.
 * @property windowBounds the last bounds of the main window, or null for the default placement.
 * @property runtimeWindowBounds the last bounds of the window that holds the detached Runtime tool window, or null for
 *   its default placement. They are screen positions like [windowBounds], so they stay with the user and the machine
 *   while whether the tool window is detached belongs to the project.
 * @property toolWindows the visibility, size and place of the tool windows, keyed by tool window name. The key is
 *   `ToolWindowId.name` from the workbench's layout package. A missing entry means the default layout. An unknown
 *   entry is kept but ignored. Part of the project.
 * @property openFiles the files open in tabs, relative to the project folder with forward slashes, in the order of
 *   the tabs. Part of the project.
 * @property activeFile the file of the active tab, named like the [openFiles], or null. Part of the project.
 * @property activeBridges the bridge files that were started with the last run, named like the [openFiles]. The
 *   run dialog has them ticked. Part of the project.
 * @property captureRuns whether the last run was started with its capture to a file switched on. The run dialog has
 *   its capture box ticked if so. Part of the project.
 * @property languageFeatures the optional language features of KStates that are switched on for the project. All are
 *   off by default. Part of the project.
 * @property otherProjectLines the lines of the project file that this version does not understand. They are kept so
 *   that they can be written back.
 */
public data class IdeSettings(
    public val recentFolders: List<String> = emptyList(),
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val editorFontSize: Float = Zoom.DEFAULT_EDITOR_FONT_SIZE,
    public val uiScale: Float = Zoom.DEFAULT_UI_SCALE,
    public val windowBounds: WindowBounds? = null,
    public val runtimeWindowBounds: WindowBounds? = null,
    public val toolWindows: Map<String, ToolWindowLayout> = emptyMap(),
    public val openFiles: List<String> = emptyList(),
    public val activeFile: String? = null,
    public val activeBridges: List<String> = emptyList(),
    public val captureRuns: Boolean = false,
    public val languageFeatures: Set<KstatesFeature> = emptySet(),
    public val otherProjectLines: List<String> = emptyList(),
) {
    init {
        require(recentFolders.size <= MAX_RECENT_FOLDERS) { "at most $MAX_RECENT_FOLDERS recent folders" }
        require(editorFontSize > 0f) { "editorFontSize must be positive" }
        require(uiScale > 0f) { "uiScale must be positive" }
    }

    /**
     * These settings with [folder] as the most recent folder. The folder moves to the front if it was listed already,
     * and the list is capped at [MAX_RECENT_FOLDERS].
     */
    public fun withRecentFolder(folder: String): IdeSettings =
        copy(recentFolders = (listOf(folder) + recentFolders.filter { it != folder }).take(MAX_RECENT_FOLDERS))

    /** These settings without [folder] among the recent folders, for example because it no longer exists. */
    public fun withoutRecentFolder(folder: String): IdeSettings = copy(recentFolders = recentFolders - folder)

    public companion object {
        /** The maximum number of recent folders. */
        public const val MAX_RECENT_FOLDERS: Int = 10
    }
}

/** The theme selection. */
@Serializable
public enum class ThemeMode {
    /** Always light. */
    LIGHT,

    /** Always dark. */
    DARK,

    /** Follow the operating system. */
    SYSTEM,
}

/** The window placement in screen pixels. A null position lets the system place the window. */
@Serializable
public data class WindowBounds(
    public val x: Int? = null,
    public val y: Int? = null,
    public val width: Int,
    public val height: Int,
    public val maximized: Boolean = false,
)

/**
 * The persisted layout of one tool window. It says whether the window is [visible] and gives its [size] in dp, which
 * is the width of a side window or the height of a bottom window. [detached] says whether the tool window is shown in
 * a window of its own instead of in its place in the main window. Only a detachable tool window is ever detached. Its
 * [size] stays the size of its docked place, so docking it again restores that size.
 */
@Serializable
public data class ToolWindowLayout(public val visible: Boolean = true, public val size: Float, public val detached: Boolean = false) {
    init {
        require(size > 0f) { "size must be positive, was $size" }
    }
}
