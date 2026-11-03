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
 * The names of the opt-in task that runs the tests against a real karpfen runtime ([TestTags.LIVE_RUNTIME]), as the
 * `karpfen.live-runtime-test` convention plugin registers it in the runtime modules. One declaration for the task, its
 * group, its two properties and its build service, which the plugin, runtime-session's ordering of the two live tasks
 * and the functional test of the plugin share.
 */
object LiveRuntimeTesting {
    /** The task that runs only the tests tagged [TestTags.LIVE_RUNTIME]; nothing depends on it. */
    const val TASK_NAME: String = "liveRuntimeTest"

    /**
     * The group of [TASK_NAME]: a group of its own, so that the task is not listed among the ordinary verification
     * tasks, which a build runs as part of `check`.
     */
    const val TASK_GROUP: String = "live runtime"

    /**
     * The URL of the runtime the live tests talk to, such as `http://127.0.0.1:49153`: the Gradle property that the
     * task reads and the system property under which the test JVM receives it.
     */
    const val URL_PROPERTY: String = "karpfen.runtime.url"

    /**
     * The folder of the runtime's logs of a live run, which failure messages of the live tests name: the Gradle
     * property and the system property of the test JVM, both optional.
     */
    const val LOGS_PROPERTY: String = "karpfen.runtime.logs"

    /** The name of the shared [LiveRuntimeLock] that keeps two live tasks from talking to the runtime at once. */
    const val LOCK_SERVICE: String = "liveRuntime"
}
