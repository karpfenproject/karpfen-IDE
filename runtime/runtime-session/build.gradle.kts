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
import io.karpfen.ide.buildlogic.LiveRuntimeTesting

/**
 * Deployment of a workspace's environment descriptor to the karpfen runtime and the session around it: the plan and
 * its preflight checks, the deploy pipeline, the observed model and active states, and the event injector. Its
 * scenarios also run against a real runtime through the live task of `karpfen.live-runtime-test`, and its test fixtures
 * turn analyzed models into the runtime's JSON for the fake runtime and offer a scriptable session for the UI tests.
 */
plugins {
    id("karpfen.live-runtime-test")
    `java-test-fixtures`
}

/**
 * A session exposes protocol types, such as the environment lifecycle, the connection and the pushes, and is prepared
 * from the environment descriptor of a workspace, so both modules are part of the API. The tests and the fixtures build
 * on the fake runtime, the synthesized models and the scenario types of runtime-protocol's fixtures, on the example
 * files of core-document's fixtures and on the virtual-time workspaces of core-workspace's fixtures.
 */
dependencies {
    api(project(":runtime:runtime-protocol"))
    api(project(":core:core-workspace"))
    implementation(libs.slf4j.api)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":runtime:runtime-protocol")))
    testImplementation(testFixtures(project(":core:core-document")))
    testImplementation(testFixtures(project(":core:core-workspace")))
    testFixturesApi(testFixtures(project(":runtime:runtime-protocol")))
    testFixturesImplementation(testFixtures(project(":core:core-document")))
    testFixturesImplementation(testFixtures(project(":core:core-workspace")))
}

/**
 * The live scenarios of the session run after those of the protocol when both live tasks run in one build: the
 * protocol's scenarios check the wire the session builds on, so a drift shows there first.
 */
tasks.named(LiveRuntimeTesting.TASK_NAME) {
    mustRunAfter(":runtime:runtime-protocol:liveRuntimeTest")
}
