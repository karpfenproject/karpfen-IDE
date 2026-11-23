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
import io.karpfen.ide.runtime.session.plan.ProjectFeature

/**
 * The settings that belong to one project, which is the folder the IDE has open. They are stored in the file
 * `.karpfenproject` in that folder, so a project carries them along when it is copied or checked out on another
 * machine. They are the look of the workbench, the layout of its tool windows, the editor panes and the files open in
 * them, which bridges were started with the last run, whether runs are captured to files, whether the runtime caches
 * results of Python code, and which optional language features and features of the project are switched on.
 *
 * Nothing in here depends on the machine. The open files are named relative to the project folder with forward
 * slashes. The place of the window on the screen and the recent folders stay in the settings of the user.
 *
 * @property theme the light or dark theme.
 * @property uiScale the factor by which the whole workbench is drawn larger or smaller, 1 for the normal size.
 * @property editorFontSize the font size of the editors in sp.
 * @property toolWindows the visibility, size and place of the tool windows, keyed by `ToolWindowId.name`.
 * @property editorPanes the editor panes with the files that are open in their tabs, the active tab of every pane and
 *   the active pane.
 * @property bridges the bridge files that were started with the last run, named like the open files.
 * @property capture whether the last run was started with its capture to a file switched on.
 * @property resultCaching whether the last run was started with the result cache of Python code on, which is the
 *   default.
 * @property languageFeatures the optional language features of KStates that are switched on for the project. All are
 *   off by default.
 * @property projectFeatures the features of the project that only switch a feature of the runtime on in its runs. All
 *   are off by default, and each one is on only while the language feature it requires is on as well.
 * @property otherLines the lines of the file that this version does not understand. They are written back unchanged,
 *   so a project file that a newer version wrote loses nothing.
 */
public data class ProjectSettings(
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    public val uiScale: Float = Zoom.DEFAULT_UI_SCALE,
    public val editorFontSize: Float = Zoom.DEFAULT_EDITOR_FONT_SIZE,
    public val toolWindows: Map<String, ToolWindowLayout> = emptyMap(),
    public val editorPanes: StoredPanes = StoredPanes.EMPTY,
    public val bridges: List<String> = emptyList(),
    public val capture: Boolean = false,
    public val resultCaching: Boolean = true,
    public val languageFeatures: Set<KstatesFeature> = emptySet(),
    public val projectFeatures: Set<ProjectFeature> = emptySet(),
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
        theme, uiScale, editorFontSize, toolWindows, editorPanes, activeBridges, captureRuns, resultCaching,
        languageFeatures, projectFeatures, otherProjectLines,
    )

/**
 * These settings with the project part replaced by [project]. The recent folders and the bounds of the main window and
 * of the detached Runtime tool window stay.
 */
public fun IdeSettings.withProject(project: ProjectSettings): IdeSettings = copy(
    theme = project.theme,
    uiScale = project.uiScale,
    editorFontSize = project.editorFontSize,
    toolWindows = project.toolWindows,
    editorPanes = project.editorPanes,
    activeBridges = project.bridges,
    captureRuns = project.capture,
    resultCaching = project.resultCaching,
    languageFeatures = project.languageFeatures,
    projectFeatures = project.projectFeatures,
    otherProjectLines = project.otherLines,
)
