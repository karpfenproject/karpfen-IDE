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
package io.karpfen.ide.app.launch

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the process does when it ends, in one shutdown hook of the Java virtual machine with a fixed order.
 *
 * The virtual machine starts all of its shutdown hooks at the same time and in no order. Two hooks that each close a
 * part of the IDE could therefore race, and a runtime host could be closed while a run on it is still being stopped.
 * So the IDE registers exactly one hook, through this class, and every part that has to be closed at exit adds a step
 * to it. The hook runs the steps of [Stage.RUNS] first and those of [Stage.HOSTS] after them, each stage in the order
 * its steps were added. A step that fails is logged, and the next step runs all the same.
 *
 * The hook is registered through [register] when the first step is added, so a sequence without steps leaves the
 * virtual machine alone. The hook cannot run when the process is killed outright or the machine loses power.
 *
 * [add] may be called from any thread. [run] is what the hook runs, and tests call it directly.
 *
 * @param register registers the thread to run when the process ends. By default it becomes a shutdown hook of the
 *   virtual machine.
 */
internal class ExitSequence(private val register: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook) {

    /** The stages of the sequence, in the order they run. */
    enum class Stage {
        /** Stops what runs on a runtime: the runs of the headless mode with their bridges and environments. */
        RUNS,

        /** Closes the runtime hosts, which stops and removes the container of a Docker host. */
        HOSTS,
    }

    /** One step: the [stage] it belongs to, its [name] for the log and its [action]. */
    private class Step(val stage: Stage, val name: String, val action: () -> Unit)

    /** The steps added so far, in the order they were added. */
    private val steps = CopyOnWriteArrayList<Step>()

    /** True once the hook was registered. Guarded by the lock of [steps]. */
    private var registered = false

    /**
     * Adds the step [action], named [name] in the log, to [stage]. The first step that is added registers the hook.
     */
    fun add(stage: Stage, name: String, action: () -> Unit) {
        val first = synchronized(steps) {
            steps += Step(stage, name, action)
            !registered.also { registered = true }
        }
        if (first) register(Thread(::run, THREAD_NAME))
    }

    /** Runs the steps stage by stage, as the class describes. */
    fun run() {
        for (stage in Stage.entries) {
            steps.filter { it.stage == stage }.forEach { step ->
                log.info("At exit: {}", step.name)
                try {
                    step.action()
                } catch (e: Exception) {
                    log.error("At exit, {} failed", step.name, e)
                }
            }
        }
    }

    private companion object {
        /** The name of the thread of the hook. */
        const val THREAD_NAME = "karpfen-ide-at-exit"

        private val log = LoggerFactory.getLogger(ExitSequence::class.java)
    }
}
