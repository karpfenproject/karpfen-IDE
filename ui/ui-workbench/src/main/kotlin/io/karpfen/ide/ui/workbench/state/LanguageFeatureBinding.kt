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
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the workspace in step with the optional language features that the settings of the project switch on. The
 * project settings are the one place where the choice lives. The workspace gets them when a folder opens and again
 * whenever they change, and its analysis follows.
 *
 * [switch] changes the choice of the open project. Without an open folder there is no project to store it in, so it
 * does nothing then.
 */
internal class LanguageFeatureBinding(
    private val holder: WorkspaceHolder,
    private val settings: SettingsRepository,
) {

    /**
     * Hands the switched-on features of the settings to the workspace of [holder] in [scope], whenever the workspace
     * or the features change. The first hand-over runs at once.
     */
    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        combine(holder.workspace, settings.settings.map { it.languageFeatures }.distinctUntilChanged()) { workspace, features ->
            workspace?.setLanguageFeatures(features)
        }.collect { }
    }

    /** Switches [feature] on or off, as [on] says, in the settings of the open project. */
    suspend fun switch(feature: KstatesFeature, on: Boolean) {
        if (holder.workspace.value == null) return
        settings.update { stored ->
            stored.copy(languageFeatures = if (on) stored.languageFeatures + feature else stored.languageFeatures - feature)
        }
    }
}
