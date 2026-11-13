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

/**
 * The settings that belong to one project, which is the folder the IDE has open. They are stored in the file
 * `.karpfenproject` in that folder, so a project carries them along when it is copied or checked out on another
 * machine. They are the look of the workbench, the layout of its tool windows, how the runtime is reached, which
 * files are open, which bridges were started with the last run, whether runs are captured to files, and which
 * optional language features are switched on.
 *
 * Nothing in here depends on the machine. The open files are named relative to the project folder with forward
 * slashes. The place of the window on the screen and the recent folders stay in the settings of the user.
 *
 * @property theme the light or dark theme.
 * @property uiScale the factor by which the whole workbench is drawn larger or smaller, 1 for the normal size.
 * @property editorFontSize the font size of the editors in sp.
 * @property toolWindows the visibility and size of the tool windows, keyed by `ToolWindowId.name`.
 * @property runtime how the IDE reaches the karpfen runtime.
 * @property openFiles the files that are open in tabs, in the order of the tabs.
 * @property activeFile the file of the active tab, or null if no tab is open.
 * @property bridges the bridge files that were started with the last run, named like the open files.
 * @property capture whether the last run was started with its capture to a file switched on.
 * @property languageFeatures the optional language features of KStates that are switched on for the project. All are
 *   off by default.
 * @property otherLines the lines of the file that this version does not understand. They are written back unchanged,
 *   so a project file that a newer version wrote loses nothing.
 */
public data class ProjectSettings(
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val uiScale: Float = Zoom.DEFAULT_UI_SCALE,
    public val editorFontSize: Float = Zoom.DEFAULT_EDITOR_FONT_SIZE,
    public val toolWindows: Map<String, ToolWindowLayout> = emptyMap(),
    public val runtime: RuntimeSettings = RuntimeSettings(),
    public val openFiles: List<String> = emptyList(),
    public val activeFile: String? = null,
    public val bridges: List<String> = emptyList(),
    public val capture: Boolean = false,
    public val languageFeatures: Set<KstatesFeature> = emptySet(),
    public val otherLines: List<String> = emptyList(),
) {
    public companion object {
        /** The name of the project file in the project folder. */
        public const val FILE_NAME: String = ".karpfenproject"
    }
}

/** The part of these settings that belongs to the open project. */
public val IdeSettings.project: ProjectSettings
    get() = ProjectSettings(
        theme, uiScale, editorFontSize, toolWindows, runtime, openFiles, activeFile, activeBridges, captureRuns,
        languageFeatures, otherProjectLines,
    )

/** These settings with the project part replaced by [project]. The recent folders and the window bounds stay. */
public fun IdeSettings.withProject(project: ProjectSettings): IdeSettings = copy(
    theme = project.theme,
    uiScale = project.uiScale,
    editorFontSize = project.editorFontSize,
    toolWindows = project.toolWindows,
    runtime = project.runtime,
    openFiles = project.openFiles,
    activeFile = project.activeFile,
    activeBridges = project.bridges,
    captureRuns = project.capture,
    languageFeatures = project.languageFeatures,
    otherProjectLines = project.otherLines,
)
