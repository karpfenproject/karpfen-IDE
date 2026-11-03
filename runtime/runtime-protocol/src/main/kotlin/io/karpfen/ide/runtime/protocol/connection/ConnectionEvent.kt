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

import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.transport.LinkClosure

/**
 * What a connection reports, in the order it happened: epoch boundaries travel in one stream with the pushes, so a
 * consumer sees which pushes came on which link.
 */
public sealed interface ConnectionEvent {
    /**
     * A link was opened and authenticated.
     *
     * @property epoch the number of the open, counting from 1.
     */
    public data class Opened(public val epoch: Int) : ConnectionEvent

    /**
     * The runtime pushed a message.
     *
     * @property push the decoded message.
     */
    public data class Received(public val push: RuntimePush) : ConnectionEvent

    /**
     * Something the runtime sent broke the protocol; the link stays open.
     *
     * @property violation how.
     */
    public data class Violated(public val violation: ProtocolViolation) : ConnectionEvent

    /**
     * The link closed without the client asking; a reconnect follows.
     *
     * @property closure how it closed.
     */
    public data class Interrupted(public val closure: LinkClosure) : ConnectionEvent

    /**
     * The connection ended for good; the last event.
     *
     * @property end why.
     */
    public data class Ended(public val end: ConnectionEnd) : ConnectionEvent
}
