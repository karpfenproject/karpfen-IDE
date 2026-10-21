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
 * Makes the workbench dialogs modal for commands (Decorator of [delegate]; decision 47 of the work-package plan): every
 * command registered through it is disabled while a dialog is shown ([dialog] not null) and does nothing if it is run
 * anyway. The dialogs are overlays inside the window, so the menu bar and its accelerators stay live; without this a
 * shortcut typed into a dialog's text field would act on the workbench behind it (Cmd+F moving the focus into an
 * editor's find bar, Cmd+W queueing a Close Tab that runs once the dialog is answered).
 *
 * Each command's enablement follows its own `isEnabled` and [dialog] in [scope] (re-registering or unregistering an id
 * stops following the replaced command). Used from the UI thread, like the registry.
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
