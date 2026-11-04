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
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.karpfen.ide.runtime.protocol.transport.ktor.ktorTransports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.CoroutineContext
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * The real karpfen runtime with IDE support that the live task talks to, usually a throwaway container that
 * `runtime/live-runtime.sh test` started and whose URL it handed to the task as the system property [URL_PROPERTY].
 *
 * Every [run] gets fresh Ktor transports to [endpoint], with a WebSocket seam that records every frame the runtime sends;
 * the scenario runs on [Dispatchers.Default] on real time with the real monotonic clock, under
 * [Patience.scenario]. After the scenario, every recorded frame is decoded with the client's frame decoder for the
 * environment and client its link's handshake names, and the run fails on any violation: the broadest check that the
 * runtime's writers and the client's reader agree. Coroutines the scenario left running are cancelled when it ends.
 *
 * @property endpoint the runtime; by default the URL in the system property [URL_PROPERTY].
 * @param runFolder the folder of the run's logs, from the system property [LOGS_PROPERTY], or null without one.
 * @throws IllegalStateException naming `runtime/live-runtime.sh test` if [URL_PROPERTY] is not set: a live test never
 *   skips.
 */
public class LiveRuntimeUnderTest(
    public val endpoint: RuntimeEndpoint = configuredEndpoint(),
    private val runFolder: Path? = configuredRunFolder(),
    override val patience: Patience = Patience.LIVE,
) : RuntimeUnderTest {

    override val name: String = endpoint.httpBase

    /** The options of a client in a scenario: the default dispatcher and the real monotonic clock. */
    private val clientOptions = RuntimeClientOptions(dispatcher = Dispatchers.Default, timeSource = TimeSource.Monotonic)

    /** The URL of the runtime and the run folder with the files it holds now. */
    private val diagnostics: String
        get() {
            val folder = runFolder ?: return "Runtime $name; no run folder (the system property $LOGS_PROPERTY is not set)."
            val files = if (Files.isDirectory(folder)) {
                Files.list(folder).use { list -> list.map { it.fileName.toString() }.sorted().toList() }
            } else {
                emptyList()
            }
            return "Runtime $name; run folder $folder with $files now, and runtime.log, traces/ and run.txt once the run is torn down."
        }

    override fun run(scenario: suspend RuntimeScenarioScope.() -> Unit) {
        val recorder = FrameRecorder()
        val transports = ktorTransports(endpoint)
        val failure = try {
            runBlocking(Dispatchers.Default) {
                val forwarding = SupervisorJob(coroutineContext.job)
                try {
                    val recorded = recorder.recording(transports, CoroutineScope(coroutineContext + forwarding))
                    withTimeout(patience.scenario) {
                        val work = Job(coroutineContext.job)
                        try {
                            LiveScenarioScope(coroutineContext + work, recorded).scenario()
                        } finally {
                            work.cancelAndJoin()
                        }
                    }
                } finally {
                    forwarding.cancel()
                }
            }
            null
        } catch (timeout: TimeoutCancellationException) {
            AssertionError("The scenario ran longer than ${patience.scenario}. $diagnostics", timeout)
        } catch (failed: Throwable) {
            failed
        } finally {
            transports.close()
        }
        val violations = recorder.violations()
        val report = "The runtime sent frames the client's reader does not accept:\n${violations.joinToString("\n")}\n$diagnostics"
        when {
            failure != null -> throw failure.also { if (violations.isNotEmpty()) it.addSuppressed(AssertionError(report)) }
            violations.isNotEmpty() -> fail(report)
        }
    }

    /** The scope of one scenario: its coroutine context, the recorded transports and this runtime's options. */
    private inner class LiveScenarioScope(
        override val coroutineContext: CoroutineContext,
        override val transports: RuntimeTransports,
    ) : RuntimeScenarioScope {
        override val clientOptions: RuntimeClientOptions get() = this@LiveRuntimeUnderTest.clientOptions
        override val patience: Patience get() = this@LiveRuntimeUnderTest.patience
        override val diagnostics: String get() = this@LiveRuntimeUnderTest.diagnostics
    }

    public companion object {
        /** The system property with the runtime's URL, such as `http://127.0.0.1:49153`. */
        public const val URL_PROPERTY: String = "karpfen.runtime.url"

        /** The system property with the folder of the run's logs. */
        public const val LOGS_PROPERTY: String = "karpfen.runtime.logs"

        /**
         * The runtime at the URL of the system property [URL_PROPERTY].
         *
         * @throws IllegalStateException naming `runtime/live-runtime.sh test` if the property is not set.
         * @throws IllegalArgumentException if it is no runtime URL.
         */
        public fun configuredEndpoint(): RuntimeEndpoint {
            val url = checkNotNull(System.getProperty(URL_PROPERTY)) {
                "The system property $URL_PROPERTY is not set. Run the live tests with runtime/live-runtime.sh test, which " +
                    "starts a runtime in Docker and passes its URL, or pass -P$URL_PROPERTY=http://<host>:<port> to liveRuntimeTest."
            }
            return RuntimeEndpoint.parse(url)
        }

        /** The run folder of the system property [LOGS_PROPERTY], or null if it is not set. */
        public fun configuredRunFolder(): Path? = System.getProperty(LOGS_PROPERTY)?.let { Paths.get(it) }
    }
}
