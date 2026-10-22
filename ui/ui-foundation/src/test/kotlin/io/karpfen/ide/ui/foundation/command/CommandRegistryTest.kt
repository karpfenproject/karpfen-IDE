package io.karpfen.ide.ui.foundation.command

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The default [CommandRegistry]: registration, replacement and removal, lookup by stroke per platform, the owner of a
 * shared shortcut, execution of enabled commands only, and the published command flow.
 */
class CommandRegistryTest {

    private val save = KeyShortcut(KeyCode.S, primary = true)

    private fun command(id: String, shortcut: KeyShortcut? = null, enabled: Boolean = true, runs: MutableList<String>? = null) =
        Command(id, title = id.uppercase(), shortcut = shortcut, isEnabled = MutableStateFlow(enabled)) { runs?.add(id) }

    @Test
    fun `a new registry is empty and keeps its platform`() {
        val registry = CommandRegistry(HostPlatform.WINDOWS)

        assertEquals(HostPlatform.WINDOWS, registry.platform)
        assertTrue(registry.commands.value.isEmpty())
        assertNull(registry.find("any"))
        assertFalse(registry.execute("any"))
    }

    @Test
    fun `the default registry uses the platform of this JVM`() {
        assertEquals(HostPlatform.current(), CommandRegistry().platform)
    }

    @Test
    fun `registered commands are found by id and published in registration order`() {
        val registry = CommandRegistry(HostPlatform.LINUX)
        val open = command("open")
        val close = command("close")

        registry.register(open)
        registry.register(close)

        assertSame(open, registry.find("open"))
        assertSame(close, registry.find("close"))
        assertEquals(listOf("open", "close"), registry.commands.value.keys.toList())
        assertEquals(listOf(open, close), registry.commands.value.values.toList())
    }

    @Test
    fun `registering an id again replaces the command`() {
        val registry = CommandRegistry(HostPlatform.LINUX)
        val first = command("save", save)
        val second = command("save", KeyShortcut(KeyCode.S, primary = true, shift = true))
        registry.register(first)
        registry.register(command("other"))

        registry.register(second)

        assertSame(second, registry.find("save"))
        assertEquals(listOf("other", "save"), registry.commands.value.keys.toList())
        assertNull(registry.commandFor(KeyStroke(KeyCode.S, setOf(KeyModifier.CTRL))), "the old shortcut is gone")
        assertSame(second, registry.commandFor(KeyStroke(KeyCode.S, setOf(KeyModifier.CTRL, KeyModifier.SHIFT))))
    }

    @Test
    fun `unregister removes the command and its shortcut and ignores unknown ids`() {
        val registry = CommandRegistry(HostPlatform.LINUX)
        registry.register(command("save", save))
        val before = registry.commands.value

        registry.unregister("unknown")
        assertSame(before, registry.commands.value, "no new map for a no-op")

        registry.unregister("save")
        assertNull(registry.find("save"))
        assertNull(registry.commandFor(save.strokeFor(HostPlatform.LINUX)))
        assertTrue(registry.commands.value.isEmpty())
    }

    @Test
    fun `lookup by stroke resolves the primary modifier per platform`() {
        val macRegistry = CommandRegistry(HostPlatform.MAC_OS)
        val pcRegistry = CommandRegistry(HostPlatform.WINDOWS)
        val command = command("save", save)
        macRegistry.register(command)
        pcRegistry.register(command)

        assertSame(command, macRegistry.commandFor(KeyStroke(KeyCode.S, setOf(KeyModifier.META))))
        assertNull(macRegistry.commandFor(KeyStroke(KeyCode.S, setOf(KeyModifier.CTRL))))
        assertSame(command, pcRegistry.commandFor(KeyStroke(KeyCode.S, setOf(KeyModifier.CTRL))))
        assertNull(pcRegistry.commandFor(KeyStroke(KeyCode.S, setOf(KeyModifier.META))))
        assertNull(pcRegistry.commandFor(KeyStroke(KeyCode.S)), "the modifiers must match exactly")
        assertNull(pcRegistry.commandFor(KeyStroke(KeyCode.S, setOf(KeyModifier.CTRL, KeyModifier.SHIFT))))
    }

    @Test
    fun `physical Ctrl on macOS is distinct from the primary modifier`() {
        val registry = CommandRegistry(HostPlatform.MAC_OS)
        val completion = command("complete", KeyShortcut(KeyCode.SPACE, ctrl = true))
        registry.register(completion)

        assertSame(completion, registry.commandFor(KeyStroke(KeyCode.SPACE, setOf(KeyModifier.CTRL))))
        assertNull(registry.commandFor(KeyStroke(KeyCode.SPACE, setOf(KeyModifier.META))))
    }

    @Test
    fun `commands without a shortcut are not reachable by stroke`() {
        val registry = CommandRegistry(HostPlatform.LINUX)
        registry.register(command("about"))

        assertNull(registry.commandFor(KeyStroke(KeyCode.A)))
    }

    @Test
    fun `the most recently registered command owns a shared shortcut until it is unregistered`() {
        val registry = CommandRegistry(HostPlatform.LINUX)
        val older = command("older", save)
        val newer = command("newer", save)
        val stroke = save.strokeFor(HostPlatform.LINUX)

        registry.register(older)
        registry.register(newer)
        assertSame(newer, registry.commandFor(stroke))

        registry.register(older)
        assertSame(older, registry.commandFor(stroke), "re-registering counts as the newest registration")

        registry.unregister("older")
        assertSame(newer, registry.commandFor(stroke))
    }

    @Test
    fun `execute runs an enabled command once and reports it`() {
        val registry = CommandRegistry(HostPlatform.LINUX)
        val runs = mutableListOf<String>()
        registry.register(command("save", save, runs = runs))

        assertTrue(registry.execute("save"))
        assertEquals(listOf("save"), runs)
    }

    @Test
    fun `disabled commands do not execute, and execution follows the live enablement`() {
        val registry = CommandRegistry(HostPlatform.LINUX)
        val runs = mutableListOf<String>()
        val enabled = MutableStateFlow(false)
        registry.register(Command("save", "Save", save, enabled) { runs += "save" })

        assertFalse(registry.execute("save"))
        assertTrue(runs.isEmpty())
        assertSame(registry.find("save"), registry.commandFor(save.strokeFor(HostPlatform.LINUX)), "lookup still finds it")

        enabled.value = true
        assertTrue(registry.execute("save"))
        assertEquals(listOf("save"), runs)
    }

    @Test
    fun `the command flow publishes every change`() {
        val registry = CommandRegistry(HostPlatform.LINUX)
        val seen = mutableListOf<Set<String>>()
        seen += registry.commands.value.keys

        registry.register(command("a"))
        seen += registry.commands.value.keys
        registry.register(command("b"))
        seen += registry.commands.value.keys
        registry.unregister("a")
        seen += registry.commands.value.keys

        assertEquals(listOf(emptySet(), setOf("a"), setOf("a", "b"), setOf("b")), seen)
    }
}
