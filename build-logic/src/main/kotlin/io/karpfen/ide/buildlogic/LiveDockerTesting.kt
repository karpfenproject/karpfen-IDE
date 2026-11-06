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
package io.karpfen.ide.buildlogic

/**
 * The names of the opt-in task of runtime-host that runs the tests against the local Docker engine
 * ([TestTags.LIVE_DOCKER]): the module's build script registers it, and the `karpfen.kotlin-jvm` convention exempts it,
 * and only it, from the exclusion of that tag.
 */
object LiveDockerTesting {
    /** The task that runs only the tests tagged [TestTags.LIVE_DOCKER]; nothing depends on it. */
    const val TASK_NAME: String = "liveDockerTest"

    /** The group of [TASK_NAME]: a group of its own, so that the task is not listed among the verification tasks. */
    const val TASK_GROUP: String = "live docker"
}
