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

import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider

/**
 * The runtime a live test task talks to, handed to the test JVM as system properties.
 *
 * Gradle asks for the arguments when it forks the test JVM, that is when the task runs and after its configuration was
 * stored, so a missing or malformed URL fails the task before any test runs, and every other task, `check` and `build`
 * included, never needs the URL. The message names the property and the script that starts a runtime and passes it.
 *
 * @property url the runtime's URL, the Gradle property [LiveRuntimeTesting.URL_PROPERTY].
 * @property logs the folder of the runtime's logs, the Gradle property [LiveRuntimeTesting.LOGS_PROPERTY].
 */
class LiveRuntimeTarget(
    @get:Input @get:Optional val url: Provider<String>,
    @get:Input @get:Optional val logs: Provider<String>,
) : CommandLineArgumentProvider {

    /**
     * The system properties `-Dkarpfen.runtime.url=…` and, if [logs] is set, `-Dkarpfen.runtime.logs=…`.
     *
     * @throws GradleException if [url] is missing or starts with neither `http://` nor `https://`.
     */
    override fun asArguments(): Iterable<String> {
        val target = url.orNull?.takeIf { value -> SCHEMES.any { value.startsWith(it) } } ?: throw GradleException(MISSING_URL)
        val log = logs.orNull?.let { "-D${LiveRuntimeTesting.LOGS_PROPERTY}=$it" }
        return listOfNotNull("-D${LiveRuntimeTesting.URL_PROPERTY}=$target", log)
    }

    private companion object {
        /** The URL schemes of a runtime: the client reaches its HTTP routes and derives the WebSocket URL from them. */
        val SCHEMES = listOf("http://", "https://")

        /** Why the task cannot run: no URL, or none that names an HTTP endpoint. */
        const val MISSING_URL: String = "${LiveRuntimeTesting.TASK_NAME} needs -P${LiveRuntimeTesting.URL_PROPERTY}=" +
            "http://<host>:<port>; runtime/live-runtime.sh test starts a runtime in Docker and passes it"
    }
}
