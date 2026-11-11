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
package io.karpfen.ide.runtime.protocol.connection

import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleRefusal
import io.karpfen.ide.runtime.protocol.transport.TransportFailure

/** How sending an event frame ended. */
public sealed interface SendOutcome {
    /**
     * The frame was written to the socket.
     *
     * @property frameText the exact text of the frame.
     */
    public data class Written(public val frameText: String) : SendOutcome

    /**
     * The connection is not open, so nothing was sent.
     *
     * @property state its state.
     */
    public data class NotConnected(public val state: ConnectionState) : SendOutcome

    /**
     * The environment's phase does not allow injecting events, so nothing was sent.
     *
     * @property refusal why.
     */
    public data class NotAllowed(public val refusal: LifecycleRefusal) : SendOutcome

    /**
     * The frame could not be written.
     *
     * @property failure why.
     */
    public data class Failed(public val failure: TransportFailure) : SendOutcome
}
