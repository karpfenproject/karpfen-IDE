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

import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Makes the workbench dialogs modal for commands. It decorates [delegate], following the Decorator pattern. Every
 * command registered through it is disabled while a dialog is shown, which is while [dialog] is not null. If such a
 * command runs anyway, it does nothing.
 *
 * The dialogs are overlays inside the window, so the menu bar and its accelerators stay live. Without this class, a
 * shortcut typed into a dialog's text field would act on the workbench behind it. Cmd+F would move the focus into an
 * editor's find bar. Cmd+W would queue a Close Tab that runs once the dialog is answered.
 *
 * The enablement of each command follows its own `isEnabled` and [dialog] in [scope]. Registering an id again, or
 * unregistering it, stops following the replaced command. Use the class from the UI thread only, like the registry.
 */
internal class ModalCommandRegistry(
    private val scope: CoroutineScope,
    private val delegate: CommandRegistry,
    private val dialog: StateFlow<DialogRequest<*>?>,
) : CommandRegistry by delegate {

    private val following = HashMap<String, Job>()

    override fun register(command: Command) {
        val enabled = MutableStateFlow(command.isEnabled.value && dialog.value == null)
        following.remove(command.id)?.cancel()
        following[command.id] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(command.isEnabled, dialog) { isEnabled, shown -> isEnabled && shown == null }.collect { enabled.value = it }
        }
        delegate.register(
            Command(command.id, command.title, command.shortcut, enabled.asStateFlow()) {
                if (dialog.value == null) command.action()
            },
        )
    }

    override fun unregister(id: String) {
        following.remove(id)?.cancel()
        delegate.unregister(id)
    }
}
