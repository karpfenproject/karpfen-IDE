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
 * Tests that [ModalCommandRegistry] disables commands and makes them do nothing while a dialog is shown, and leaves
 * them unchanged otherwise. The test simulates a menu that runs a disabled command anyway by calling the registered
 * command's action directly.
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
