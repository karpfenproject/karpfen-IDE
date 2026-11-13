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
package io.karpfen.ide.runtime.host

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * A host factory that knows which of its hosts are still open, and closes them when it is closed itself.
 *
 * It creates its hosts through [hosts] and hands each one out in a wrapper that behaves like the host. The factory
 * keeps the wrapper until the host is closed. [close] closes every host that is still open, which stops and removes the
 * container of the host. A host that is being closed elsewhere at that moment is not closed a second time. [close]
 * waits until that close has finished, so it returns only when no host of this factory owns a runtime any more. After
 * [close] the factory still creates hosts, but closes each one at once, so it starts nothing.
 *
 * An application closes the factory when its process ends. The runtime of a workspace is then stopped however the
 * process ends, as long as the process still gets to run code: when the window was closed, when the process was asked
 * to terminate, and when it exits while a host is still stopping its container.
 *
 * Every member is safe to call from any thread. [close] blocks as closing a host does.
 *
 * @param hosts creates the hosts.
 */
public class OpenRuntimeHosts(private val hosts: RuntimeHostFactory) : RuntimeHostFactory, AutoCloseable {
    /** The hosts that were created and are not closed yet. */
    private val open: MutableSet<Tracked> = ConcurrentHashMap.newKeySet()

    /** True once [close] began. */
    @Volatile
    private var closed = false

    override fun create(workspaceRoot: Path): RuntimeHost {
        val host = Tracked(hosts.create(workspaceRoot))
        open += host
        if (closed) host.close()
        return host
    }

    override fun close() {
        closed = true
        open.toList().forEach { it.close() }
    }

    /**
     * The host [host], known to the factory until it is closed.
     *
     * @property host the host that does the work.
     */
    private inner class Tracked(private val host: RuntimeHost) : RuntimeHost by host {
        /** Closes [host] once. A second caller waits for the first and then finds the host closed. */
        private val closing = lazy { host.close() }

        override fun close() {
            try {
                closing.value
            } finally {
                open -= this
            }
        }
    }
}
