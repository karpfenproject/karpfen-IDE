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
package io.karpfen.ide.runtime.protocol.doubles

import io.karpfen.ide.runtime.protocol.RuntimeClientOptions
import io.karpfen.ide.runtime.protocol.conformance.Patience
import io.karpfen.ide.runtime.protocol.conformance.RuntimeScenarioScope
import io.karpfen.ide.runtime.protocol.conformance.RuntimeUnderTest
import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A runtime under test that runs every scenario on virtual time against the transports of [fake], so that a test of
 * the scenario harness sees the requests a scenario made. It stands in for no real runtime. The fake answers only what
 * the test programmed, and the scenarios of the contract fail against it. Coroutines a scenario leaves running are
 * cancelled when it ends, as with the live runtime.
 *
 * @property fake the fake runtime the scenarios reach.
 */
internal class FakeRuntimeUnderTest(val fake: FakeRuntime) : RuntimeUnderTest {

    override val name: String = "the fake runtime"

    override val patience: Patience = Patience.LIVE

    override fun run(scenario: suspend RuntimeScenarioScope.() -> Unit) {
        runTest { VirtualScenarioScope(this).scenario() }
    }

    /** The scope of one scenario in the background of [test], on its virtual time, with the fake's transports. */
    private inner class VirtualScenarioScope(test: TestScope) : RuntimeScenarioScope, CoroutineScope by test.backgroundScope {
        override val transports: RuntimeTransports = fake.transports()
        override val clientOptions: RuntimeClientOptions =
            RuntimeClientOptions(dispatcher = StandardTestDispatcher(test.testScheduler), timeSource = test.testScheduler.timeSource)
        override val patience: Patience get() = this@FakeRuntimeUnderTest.patience
        override val diagnostics: String = this@FakeRuntimeUnderTest.name
    }
}
