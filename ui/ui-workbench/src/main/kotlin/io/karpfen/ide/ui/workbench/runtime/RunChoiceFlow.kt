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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.ui.workbench.bridges.BridgeLoader
import io.karpfen.ide.ui.workbench.settings.SettingsRepository

/**
 * Asks how to run a descriptor of [workspace] and remembers the answer in the project. The run dialog lists the bridge
 * files of the workspace with the bridges of the last run ticked, and the box that captures the run to a file, ticked
 * if the last run was captured. The choice is written to the settings only if it differs from what they hold.
 *
 * Use the class from the UI thread only.
 *
 * @param settings the IDE settings, whose project part remembers the choice.
 * @param support shows the run dialog.
 */
internal class RunChoiceFlow(
    private val workspace: Workspace,
    private val settings: SettingsRepository,
    private val support: RunSupport,
) {
    /** Shows the run dialog and remembers the answer. Returns the choice, or null if the user cancelled the run. */
    suspend fun ask(): RunChoice? {
        val root = workspace.root
        val files = workspace.files.value.files(FileKind.BRIDGE).map { it.path }
        val stored = settings.settings.value
        val last = stored.activeBridges.toSet()
        val ticked = files.filter { BridgeLoader.nameOf(root, it) in last }.toSet()
        val choice = support.configureRun(root, files, ticked, stored.captureRuns) ?: return null
        val names = choice.bridges.map { BridgeLoader.nameOf(root, it) }
        val current = settings.settings.value
        if (names != current.activeBridges || choice.capture != current.captureRuns) {
            settings.update { it.copy(activeBridges = names, captureRuns = choice.capture) }
        }
        return choice
    }
}
