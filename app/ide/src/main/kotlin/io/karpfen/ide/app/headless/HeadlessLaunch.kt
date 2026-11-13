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
package io.karpfen.ide.app.headless

import io.karpfen.ide.app.IdeApplication
import io.karpfen.ide.app.IdeSession
import io.karpfen.ide.app.launch.ExitSequence
import io.karpfen.ide.app.launch.runtimeHostsClosedAtExit
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.host.RuntimeHosts
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The composition root of the headless mode. It carries out a [HeadlessCommand] with the IDE's own wiring, without a
 * window, and returns the exit status from [HeadlessExitCodes].
 *
 * Each command opens its folder through `IdeApplication.prepare` over the production services of the workbench, with
 * [ReadOnlySettings] in place of the settings the window reads and writes. `validate` gets no runtime hosts, so it
 * never reaches a runtime. `run` gets the runtime hosts of [hosts], which are the IDE's Docker container of the
 * workspace unless a test hands in its own.
 *
 * The workbench state is driven from one thread of its own, as the window drives it from the UI thread. The calling
 * thread waits for the command.
 *
 * A run adds two steps to the one shutdown hook of [atExit]. Its step of the runs asks the run to stop through a
 * [HeadlessStop] and waits for it to shut down its bridges, its environment and the runtime, for at most
 * [STOP_BOUND]. The step of the hosts, from `runtimeHostsClosedAtExit`, then closes whatever host is still open. So
 * Ctrl+C or a terminate signal shuts everything down in that order before the process ends.
 *
 * A failure of the tool itself is logged and ends with [HeadlessExitCodes.FAILURE].
 *
 * @param console where the commands write.
 * @param atExit the one sequence of the process that runs when it ends.
 * @param hosts creates the runtime host of the folder of a run.
 */
internal class HeadlessLaunch(
    private val console: HeadlessConsole = HeadlessConsole(),
    private val atExit: ExitSequence = ExitSequence(),
    private val hosts: RuntimeHostFactory = RuntimeHosts(),
) {
    /** Carries out [command] and returns its exit status. */
    fun run(command: HeadlessCommand): Int = when (command) {
        HeadlessCommand.Help -> {
            console.line(HeadlessUsage.TEXT)
            HeadlessExitCodes.OK
        }
        is HeadlessCommand.Invalid -> {
            console.message(command.message)
            console.error(HeadlessUsage.TEXT)
            HeadlessExitCodes.USAGE
        }
        is HeadlessCommand.Validate -> onOwnThread { scope ->
            HeadlessValidation(console, prepareWith(hosts = null)).validate(scope, command)
        }
        is HeadlessCommand.Run -> run(command)
    }

    /** Carries out the run [command], with its steps in the shutdown hook, as the class describes. */
    private fun run(command: HeadlessCommand.Run): Int {
        val stop = HeadlessStop()
        atExit.add(ExitSequence.Stage.RUNS, "stop the headless run") {
            if (!stop.requestAndAwait(STOP_BOUND)) log.warn("The headless run did not shut down within {}", STOP_BOUND)
        }
        val open = runtimeHostsClosedAtExit(hosts, atExit)
        return try {
            onOwnThread { scope -> HeadlessRun(console, prepareWith(open), stop).run(scope, command) }
        } finally {
            stop.finished()
        }
    }

    /**
     * How a command opens its folder: `IdeApplication.prepare` over the headless services with [hosts], or with the
     * workbench's hosts that never have a runtime when [hosts] is null.
     */
    private fun prepareWith(hosts: RuntimeHostFactory?): suspend (CoroutineScope, Path) -> IdeSession {
        val application = IdeApplication(
            services = { output ->
                if (hosts == null) {
                    defaultWorkbenchServices(languageToolsOutput = output, settings = ReadOnlySettings())
                } else {
                    defaultWorkbenchServices(
                        languageToolsOutput = output,
                        runtimeSessions = RuntimeSessions(),
                        runtimeHosts = hosts,
                        settings = ReadOnlySettings(),
                    )
                }
            },
        )
        return { scope, folder -> application.prepare(scope, folder) }
    }

    /**
     * Runs [command] on a thread of its own, in a scope whose dispatcher is that thread, waits for it and returns its
     * status. The scope is cancelled and the thread ends afterwards. A failure is logged and written, and gives
     * [HeadlessExitCodes.FAILURE].
     */
    private fun onOwnThread(command: suspend (CoroutineScope) -> Int): Int {
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, THREAD_NAME) }.asCoroutineDispatcher()
        return try {
            runBlocking(dispatcher) {
                val scope = CoroutineScope(dispatcher + SupervisorJob())
                try {
                    command(scope)
                } finally {
                    scope.cancel()
                }
            }
        } catch (e: Exception) {
            log.error("The headless command failed", e)
            console.message("the command failed: ${e.message ?: e::class.java.name}")
            HeadlessExitCodes.FAILURE
        } finally {
            dispatcher.close()
        }
    }

    companion object {
        /** The system property that keeps AWT from ever reaching for a display. */
        const val AWT_HEADLESS_PROPERTY: String = "java.awt.headless"

        /**
         * How long the step of the runs in the shutdown hook waits for the run to shut down. It covers the bound of
         * `WorkbenchState.shutdownRuntime`, 15 seconds, with time to spare.
         */
        val STOP_BOUND: Duration = 20.seconds

        /** The name of the thread that drives the workbench state. */
        private const val THREAD_NAME = "karpfen-ide-headless"

        private val log = LoggerFactory.getLogger(HeadlessLaunch::class.java)
    }
}
