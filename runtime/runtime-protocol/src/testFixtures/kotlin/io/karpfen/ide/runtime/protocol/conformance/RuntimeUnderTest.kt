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
package io.karpfen.ide.runtime.protocol.conformance

import io.karpfen.ide.runtime.protocol.RuntimeClientOptions
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import kotlinx.coroutines.CoroutineScope

/**
 * A runtime the scenario classes run against: a real karpfen runtime with IDE support, reached by URL in the live task
 * (`LiveRuntimeUnderTest`). A scenario class holds the scenarios as test functions and has a live subclass, which only
 * supplies this. Failures only a fake can produce are no scenarios: they are ordinary tests of the client against the
 * fake runtime with hardcoded responses.
 *
 * Threading (MUST): [run] blocks the calling test thread until the scenario ended, and runs the scenario on the
 * runtime's own dispatchers.
 *
 * Contract (MUST):
 * - Every [run] gets fresh transports and fails the test if the scenario fails or runs longer than [Patience.scenario].
 * - A scenario creates its own environments and client ids, never assumes a fresh runtime, and leaves its environments
 *   stopped.
 */
public interface RuntimeUnderTest {
    /** The name failure messages use, such as the URL of the runtime. */
    public val name: String

    /** How long the scenarios wait for this runtime. */
    public val patience: Patience

    /** Runs [scenario] against this runtime. */
    public fun run(scenario: suspend RuntimeScenarioScope.() -> Unit)
}

/**
 * What a scenario has while it runs: the transports to the runtime, the client options that fit them, its patience and
 * what failure messages should name. The scope's coroutine context is the scenario's; long-lived work such as a
 * connection belongs in a child scope of it.
 *
 * Threading (MUST): the scope is used from the scenario's coroutine and its children.
 *
 * Contract (MUST): [transports] stay open while the scenario runs and are closed when it ends; [clientOptions] carry
 * the dispatcher and time source the scenario runs on (real time against a real runtime, virtual time in a test of the
 * waiting helpers).
 */
public interface RuntimeScenarioScope : CoroutineScope {
    /** The transports to the runtime under test. */
    public val transports: RuntimeTransports

    /** The options of a client of the runtime under test. */
    public val clientOptions: RuntimeClientOptions

    /** How long the scenario waits. */
    public val patience: Patience

    /** What a failure message adds about the runtime, such as its URL and where its logs are. */
    public val diagnostics: String
}
