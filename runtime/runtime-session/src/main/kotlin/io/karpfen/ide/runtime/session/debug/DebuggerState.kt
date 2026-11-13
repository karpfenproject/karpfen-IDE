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
package io.karpfen.ide.runtime.session.debug

import io.karpfen.ide.runtime.protocol.message.EngineHalt

/**
 * Where the engine of a session's environment is paused. Immutable.
 */
public sealed interface PausePlace {
    /** The engine waits between two ticks, after a pause or after a step that ended its tick. */
    public data object BetweenTicks : PausePlace

    /**
     * The engine waits at a breakpoint, in the middle of a tick.
     *
     * @property halt the breakpoint: a state with its entry or exit, or a transition with its start or end.
     */
    public data class AtBreakpoint(public val halt: EngineHalt) : PausePlace
}

/** What the debugger of a session shows. Immutable. */
public sealed interface DebuggerState {
    /**
     * There is nothing to debug. The session has no debugger, because its deployment did not switch the runtime's
     * feature `Breakpoint` on, or the session's environment does not run.
     */
    public data object Unavailable : DebuggerState

    /** The engine runs. It can be paused. */
    public data object Running : DebuggerState

    /**
     * The engine is paused. It can be resumed or stepped.
     *
     * @property place where.
     */
    public data class Paused(public val place: PausePlace) : DebuggerState
}
