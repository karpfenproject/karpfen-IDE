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
 * A runtime a scenario runs against: the fake runtime on virtual time in every build, or a real runtime with IDE support
 * reached by URL in the live task. A scenario class holds the scenarios as test functions and has one subclass per
 * runtime, which only supplies this; so one scenario checks the fake and the real runtime alike.
 *
 * Threading (MUST): [run] blocks the calling test thread until the scenario ended, and runs the scenario on the
 * runtime's own dispatchers: a test dispatcher for the fake, the default dispatcher for a real runtime.
 *
 * Contract (MUST):
 * - Every [run] gets fresh transports and fails the test if the scenario fails or runs longer than [Patience.scenario].
 * - A scenario creates its own environments and client ids, never assumes a fresh runtime, and leaves its environments
 *   stopped; scenarios that harm the whole runtime or need failures only a fake can script check [traits] first.
 */
public interface RuntimeUnderTest {
    /** The name failure messages use, such as `fake` or the URL of the real runtime. */
    public val name: String

    /** What the scenarios may do with this runtime. */
    public val traits: RuntimeTraits

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
 * the dispatcher and time source of this runtime (virtual time for the fake, real time live).
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

/**
 * What the scenarios may do with a runtime under test.
 *
 * @property runtimeWideHazards scenarios may harm the whole runtime: stall its one broadcaster with a slow consumer,
 *   restart it, make it unreachable. Only a fake allows it.
 * @property scriptedFailures scenarios may script failures a real runtime cannot be made to show on demand: an engine
 *   that halts, a half-open link, a dropped push, a runtime without IDE support. Only a fake allows it.
 */
public data class RuntimeTraits(public val runtimeWideHazards: Boolean, public val scriptedFailures: Boolean)
