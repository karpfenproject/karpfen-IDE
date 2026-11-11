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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.session.events.InjectionAvailability

/** A command of a session's lifecycle or observation, as the conductor carries it out. Immutable. */
internal sealed interface SessionCommand {
    /** What the command does, in words, as a refusal names it. */
    val description: String

    /** Starts the activated environment. */
    data object Start : SessionCommand {
        override val description: String = "Starting the environment"
    }

    /** Stops the environment. */
    data object Stop : SessionCommand {
        override val description: String = "Stopping the environment"
    }

    /**
     * Forces the machine attached to [attachedTo] into [state].
     *
     * @property attachedTo the key of the machine's object.
     * @property state the state's name.
     */
    data class ForceState(val attachedTo: String, val state: String) : SessionCommand {
        override val description: String = "Forcing the state of '$attachedTo'"
    }

    /**
     * Switches observation on or off.
     *
     * @property on true to switch it on.
     */
    data class SetObservation(val on: Boolean) : SessionCommand {
        override val description: String = if (on) "Switching observation on" else "Switching observation off"
    }

    /** Reads a snapshot of the model once. */
    data object Pull : SessionCommand {
        override val description: String = "Pulling the model"
    }
}

/**
 * Which commands a session takes in which state. Stateless.
 *
 * | State | Allowed |
 * |---|---|
 * | deploying | none |
 * | ready | start, stop, force state, observation on and off, pull, injecting events |
 * | running | stop, force state, observation on and off, pull, injecting events |
 * | halted | stop, observation off, pull |
 * | stopping | none |
 * | stopped | pull |
 * | failed, closed | none |
 *
 * Events injected while the session is ready are queued until the start. Shutting down and closing are not commands of
 * this table. The conductor handles them on their own.
 */
internal object CommandRules {
    /** Why [command] is refused in [state], or null if it is allowed. */
    fun refusal(command: SessionCommand, state: SessionState): String? {
        val allowed = when (state) {
            is SessionState.Ready -> true
            is SessionState.Running -> command != SessionCommand.Start
            is SessionState.Halted ->
                command == SessionCommand.Stop || command == SessionCommand.Pull || command == SessionCommand.SetObservation(on = false)
            is SessionState.Stopped -> command == SessionCommand.Pull
            SessionState.Deploying, is SessionState.Stopping, is SessionState.Failed, SessionState.Closed -> false
        }
        return if (allowed) null else "${command.description} is not possible while ${describe(state)}."
    }

    /** Why injecting an event is refused in [state], or null if it is allowed. */
    fun injectionRefusal(state: SessionState): String? =
        if (state is SessionState.Ready || state is SessionState.Running) null else "Events cannot be injected while ${describe(state)}."

    /** [state] in words, as the end of a sentence. */
    fun describe(state: SessionState): String =
        when (state) {
            SessionState.Deploying -> "the deployment runs"
            is SessionState.Ready -> "the environment is ready to start"
            is SessionState.Running -> "the environment runs"
            is SessionState.Halted -> "the environment's engine has halted"
            is SessionState.Stopping -> "the environment is being stopped"
            is SessionState.Stopped -> "the environment is stopped"
            is SessionState.Failed -> "the session has failed"
            SessionState.Closed -> "the session is closed"
        }
}

/**
 * Whether events can be injected, following the session's state. While the session deploys, injection is not possible
 * yet. While it is ready, events are queued until the start. While it runs, events go out live. Ready and running both
 * need an open connection. In every other state injection is unavailable. Stateless.
 */
internal object InjectionAvailabilities {
    /** The availability in [state], with a connection that is open if [linkOpen]. */
    fun of(state: SessionState, linkOpen: Boolean): InjectionAvailability =
        when (state) {
            SessionState.Deploying -> InjectionAvailability.NotYet
            is SessionState.Ready -> if (linkOpen) InjectionAvailability.Queued else notConnected()
            is SessionState.Running -> if (linkOpen) InjectionAvailability.Live else notConnected()
            else -> InjectionAvailability.Unavailable("Events cannot be injected while ${CommandRules.describe(state)}.")
        }

    /** The availability while the connection is not open. */
    private fun notConnected(): InjectionAvailability = InjectionAvailability.Unavailable("The connection to the runtime is not open.")
}
