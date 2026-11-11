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
package io.karpfen.ide.ui.foundation.command

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The application's commands. The registry does not depend on Compose. Lookups take [KeyStroke]s, and a Compose key
 * event becomes one through `KeyEvent.toKeyStroke()`. The window menu bar is built from [MenuSpec]s whose items refer
 * to command ids. Use the registry from the UI thread only.
 */
public interface CommandRegistry {

    /** The platform shortcuts are resolved for. */
    public val platform: HostPlatform

    /** Registered commands by id. */
    public val commands: StateFlow<Map<String, Command>>

    /** Registers [command], replacing a command with the same id. */
    public fun register(command: Command)

    /** Removes the command [id]. Does nothing if no such command is registered. */
    public fun unregister(id: String)

    /** The command [id], or null. */
    public fun find(id: String): Command?

    /** The command whose shortcut produces [stroke] on [platform], or null. */
    public fun commandFor(stroke: KeyStroke): Command?

    /** Runs the command [id] if it is registered and enabled, and returns whether it ran. */
    public fun execute(id: String): Boolean
}

/** An empty [CommandRegistry] for [platform]. */
public fun CommandRegistry(platform: HostPlatform = HostPlatform.current()): CommandRegistry =
    DefaultCommandRegistry(platform)

/**
 * The default registry. [commands] lists the commands in registration order, and a replaced command counts as
 * registered anew. When several commands share a shortcut, the most recently registered one answers [commandFor].
 * Unregistering it makes the previous one answer again. [commandFor] returns disabled commands too, because the caller
 * decides whether a stroke bound to a disabled command is consumed. [execute] runs only enabled commands.
 */
internal class DefaultCommandRegistry(override val platform: HostPlatform) : CommandRegistry {

    private val registered = MutableStateFlow<Map<String, Command>>(emptyMap())

    /** The command of each stroke, derived from [registered] on every change. Used on the UI thread only. */
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
     * Publishes [next] as the registered commands and rebuilds [byStroke] from it. `toMap` keeps the last pair of a key
     * and [next] lists the commands in registration order, so the most recently registered command owns a stroke that
     * several shortcuts share.
     */
    private fun publish(next: Map<String, Command>) {
        byStroke = next.values
            .mapNotNull { command -> command.shortcut?.let { shortcut -> shortcut.strokeFor(platform) to command } }
            .toMap()
        registered.value = next
    }
}
