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

import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.host.RuntimeHostSettings
import kotlinx.serialization.Serializable

/**
 * The persistent IDE settings, stored as JSON by the [SettingsRepository]. Unknown JSON keys are ignored and missing
 * keys take their defaults, so files written by older and newer versions load.
 *
 * @property recentFolders the absolute paths of recently opened folders, most recent first. There are at most
 *   [MAX_RECENT_FOLDERS] of them.
 * @property theme light, dark, or following the system.
 * @property editorFontSize the editor font size in sp.
 * @property windowBounds the last bounds of the main window, or null for the default placement.
 * @property toolWindows the visibility and size of the tool windows, keyed by tool window name. The key is
 *   `ToolWindowId.name` from the workbench's layout package. A missing entry means the default layout. An unknown
 *   entry is kept but ignored. A new tool window therefore needs no change of the settings format.
 * @property runtime how the IDE reaches the karpfen runtime.
 */
@Serializable
public data class IdeSettings(
    public val recentFolders: List<String> = emptyList(),
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val editorFontSize: Float = 13f,
    public val windowBounds: WindowBounds? = null,
    public val toolWindows: Map<String, ToolWindowLayout> = emptyMap(),
    public val runtime: RuntimeSettings = RuntimeSettings(),
) {
    init {
        require(recentFolders.size <= MAX_RECENT_FOLDERS) { "at most $MAX_RECENT_FOLDERS recent folders" }
        require(editorFontSize > 0f) { "editorFontSize must be positive" }
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
 * is the width of a side window or the height of a bottom window.
 */
@Serializable
public data class ToolWindowLayout(public val visible: Boolean = true, public val size: Float) {
    init {
        require(size > 0f) { "size must be positive, was $size" }
    }
}

/**
 * How the IDE reaches the karpfen runtime. Either the IDE owns the runtime in a Docker container, or the runtime was
 * started elsewhere and is reached at [url].
 *
 * @property host the kind of host.
 * @property url the base URL of a runtime started elsewhere, used when [host] is [RuntimeHostKind.URL].
 */
@Serializable
public data class RuntimeSettings(
    public val host: RuntimeHostKind = RuntimeHostKind.DOCKER,
    public val url: String = RuntimeHostSettings.DEFAULT_URL,
) {
    /** These settings as the runtime host reads them. */
    public fun toHostSettings(): RuntimeHostSettings = RuntimeHostSettings(host, url)
}
