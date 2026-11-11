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

import io.karpfen.ide.runtime.protocol.conformance.LiveRuntime
import io.karpfen.ide.runtime.protocol.conformance.LiveRuntimeUnderTest

/**
 * Covers injecting events through a session against the real runtime that the live task is given. The scenarios are
 * those of [InjectionScenarios]. The runtime is usually a throwaway container of `runtime/live-runtime.sh test`.
 */
@LiveRuntime
class LiveInjectionScenariosTest : InjectionScenarios() {
    override val runtime: SessionUnderTest = LiveRuntimeUnderTest().let { SessionUnderTest(it, it.endpoint) }
}
