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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Registers one command per optional language feature of KStates, `WorkbenchCommandIds.languageFeature`, which
 * switches the feature on or off for the open project through [intents]. A menu entry cannot show a check mark, so the
 * title says what a click does: `Switch On Histories` while histories are off and `Switch Off Histories` while they
 * are on. The commands are registered again with the new title whenever the features in the settings change. They
 * are enabled while a folder is open in [workspace].
 */
internal class LanguageFeatureCommands(
    private val scope: CoroutineScope,
    private val intents: WorkbenchState,
    private val workspace: StateFlow<Workspace?>,
    private val settings: SettingsRepository,
) {

    /** Registers the commands in [registry] and keeps their titles in step with the settings. */
    fun register(registry: CommandRegistry) {
        val open = workspace.map { it != null }.stateIn(scope, SharingStarted.Eagerly, workspace.value != null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settings.settings.map { it.languageFeatures }.distinctUntilChanged().collect { switchedOn ->
                KstatesFeature.entries.forEach { feature ->
                    val on = feature in switchedOn
                    registry.register(
                        Command(WorkbenchCommandIds.languageFeature(feature), titleOf(feature, on), isEnabled = open) {
                            intents.switchLanguageFeature(feature, !on)
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** The title of the switch of [feature], which says what a click does while the feature is [on] or off. */
        fun titleOf(feature: KstatesFeature, on: Boolean): String =
            "Switch ${if (on) "Off" else "On"} ${feature.displayName}"
    }
}
