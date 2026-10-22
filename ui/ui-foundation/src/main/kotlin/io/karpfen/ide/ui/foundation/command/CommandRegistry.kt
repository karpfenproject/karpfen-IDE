package io.karpfen.ide.ui.foundation.command

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The application's commands. Compose-free: lookups take [KeyStroke]s (convert Compose events with
 * `KeyEvent.toKeyStroke()`); the window menu bar is built from [MenuSpec]s whose items reference command ids.
 * Used from the UI thread only.
 */
public interface CommandRegistry {

    /** The platform shortcuts are resolved for. */
    public val platform: HostPlatform

    /** Registered commands by id. */
    public val commands: StateFlow<Map<String, Command>>

    /** Registers [command], replacing a command with the same id. */
    public fun register(command: Command)

    /** Removes the command [id]; no-op if absent. */
    public fun unregister(id: String)

    /** The command [id], or null. */
    public fun find(id: String): Command?

    /** The command whose shortcut produces [stroke] on [platform], or null. */
    public fun commandFor(stroke: KeyStroke): Command?

    /** Runs the command [id] if it is registered and enabled; returns whether it ran. */
    public fun execute(id: String): Boolean
}

/** An empty [CommandRegistry] for [platform]. */
public fun CommandRegistry(platform: HostPlatform = HostPlatform.current()): CommandRegistry =
    DefaultCommandRegistry(platform)

/**
 * The default registry. [commands] lists the commands in registration order (a replaced command counts as registered
 * anew). When several commands share a shortcut, the most recently registered one answers [commandFor]; unregistering
 * it makes the previous one answer again. [commandFor] returns disabled commands too (the caller decides whether a
 * stroke bound to a disabled command is consumed); [execute] runs only enabled ones.
 */
internal class DefaultCommandRegistry(override val platform: HostPlatform) : CommandRegistry {

    private val registered = MutableStateFlow<Map<String, Command>>(emptyMap())

    /** Derived from [registered] on every change; read and written on the UI thread only. */
    private var byStroke: Map<KeyStroke, Command> = emptyMap()

    override val commands: StateFlow<Map<String, Command>> = registered.asStateFlow()

    override fun register(command: Command) {
        publish(registered.value - command.id + (command.id to command))
    }

    override fun unregister(id: String) {
        if (id in registered.value) publish(registered.value - id)
    }

    override fun find(id: String): Command? = registered.value[id]

    override fun commandFor(stroke: KeyStroke): Command? = byStroke[stroke]

    override fun execute(id: String): Boolean {
        val command = find(id) ?: return false
        if (!command.isEnabled.value) return false
        command.action()
        return true
    }

    /**
     * Publishes [next] as the registered commands and rebuilds [byStroke] from it. `toMap` keeps the last pair of a
     * key and [next] lists the commands in registration order, so the most recently registered command owns a stroke
     * that several shortcuts share.
     */
    private fun publish(next: Map<String, Command>) {
        byStroke = next.values
            .mapNotNull { command -> command.shortcut?.let { shortcut -> shortcut.strokeFor(platform) to command } }
            .toMap()
        registered.value = next
    }
}
