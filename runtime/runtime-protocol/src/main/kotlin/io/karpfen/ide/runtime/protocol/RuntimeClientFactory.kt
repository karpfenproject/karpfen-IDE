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
package io.karpfen.ide.runtime.protocol

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.karpfen.ide.runtime.protocol.transport.ktor.ktorTransports

/**
 * Creates a client of the runtime at [endpoint] that runs as [options] say. It reaches the runtime through
 * [transports], which by default are the Ktor transports to [endpoint]. Tests hand in the fake runtime's transports and
 * a test dispatcher. The client owns the transports, so closing it closes them.
 */
public fun RuntimeClient(
    endpoint: RuntimeEndpoint,
    options: RuntimeClientOptions = RuntimeClientOptions(),
    transports: RuntimeTransports = ktorTransports(endpoint),
): RuntimeClient = DefaultRuntimeClient(endpoint, options, transports)
