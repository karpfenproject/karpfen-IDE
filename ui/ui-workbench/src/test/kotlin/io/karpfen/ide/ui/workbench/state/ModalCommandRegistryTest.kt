package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ModalCommandRegistry]: commands are disabled and inert while a dialog is shown, and otherwise unchanged. A menu that
 * runs a disabled command anyway is simulated by calling the registered command's action directly.
 */
class ModalCommandRegistryTest {

    private val dialog = MutableStateFlow<DialogRequest<*>?>(null)
    private val error = DialogRequest.Error("Cannot open folder", "gone")

    @Test
    fun `a command follows its own enablement and is disabled and inert while a dialog is shown`() = runTest {
        val registry = CommandRegistry(HostPlatform.MAC_OS)
        val modal = ModalCommandRegistry(backgroundScope, registry, dialog)
        val ownEnablement = MutableStateFlow(true)
        var runs = 0
        modal.register(Command("save", "Save", isEnabled = ownEnablement) { runs++ })

        assertTrue(registry.find("save")!!.isEnabled.value)
        assertTrue(registry.execute("save"))
        ownEnablement.value = false
        runCurrent()
        assertFalse(registry.find("save")!!.isEnabled.value, "its own enablement still counts")

        ownEnablement.value = true
        dialog.value = error
        runCurrent()
        assertFalse(registry.find("save")!!.isEnabled.value)
        assertFalse(registry.execute("save"))
        registry.find("save")!!.action()
        assertEquals(1, runs, "nothing runs behind a dialog")

        dialog.value = null
        runCurrent()
        assertTrue(registry.execute("save"))
        assertEquals(2, runs)
    }

    @Test
    fun `re-registering and unregistering an id replace and remove its command`() = runTest {
        val registry = CommandRegistry(HostPlatform.MAC_OS)
        val modal = ModalCommandRegistry(backgroundScope, registry, dialog)
        val ran = mutableListOf<String>()
        modal.register(Command("recent.0", "/a") { ran += "/a" })
        modal.register(Command("recent.0", "/b") { ran += "/b" })

        assertEquals("/b", registry.find("recent.0")!!.title)
        assertTrue(modal.execute("recent.0"))
        assertEquals(listOf("/b"), ran)

        modal.unregister("recent.0")
        assertNull(registry.find("recent.0"))
    }
}
