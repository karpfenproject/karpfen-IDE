package io.karpfen.ide.ui.foundation.command

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.flow.StateFlow

/**
 * The application's commands (spec §6). Compose-free: lookups take [KeyStroke]s (convert Compose events with
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

internal class DefaultCommandRegistry(override val platform: HostPlatform) : CommandRegistry {

    override val commands: StateFlow<Map<String, Command>>
        get() = TODO("WP-FOUNDATION: command map")

    override fun register(command: Command): Unit = TODO("WP-FOUNDATION: register")

    override fun unregister(id: String): Unit = TODO("WP-FOUNDATION: unregister")

    override fun find(id: String): Command? = TODO("WP-FOUNDATION: find")

    override fun commandFor(stroke: KeyStroke): Command? = TODO("WP-FOUNDATION: shortcut lookup")

    override fun execute(id: String): Boolean = TODO("WP-FOUNDATION: execute")
}
