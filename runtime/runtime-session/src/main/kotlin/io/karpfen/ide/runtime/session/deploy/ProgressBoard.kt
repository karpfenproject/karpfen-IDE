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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey

/**
 * The progress of one deployment, published to its listener after every transition as an immutable [DeployProgress].
 * Confined to the deploying coroutine.
 *
 * @param steps the steps of the deployment, all pending at first.
 * @param listener who hears the progress.
 */
internal class ProgressBoard(steps: List<DeployStep>, private val listener: DeployListener) {
    /** The progress as last published. */
    var progress: DeployProgress = DeployProgress(steps.map { StepProgress(it.kind, it.title, StepStatus.PENDING, null) }, null)
        private set

    /** Publishes the progress as it is, all steps pending at first. */
    fun publish() {
        listener.progress(progress)
    }

    /** Records the environment's [key] and publishes. */
    fun environment(key: EnvironmentKey) {
        progress = progress.copy(environment = key)
        publish()
    }

    /** Sets the step at [index] to [status] with [detail] and publishes, unless the step already stands so. */
    fun update(index: Int, status: StepStatus, detail: String?) {
        val updated = progress.steps[index].copy(status = status, detail = detail)
        if (updated == progress.steps[index]) return
        progress = progress.copy(steps = progress.steps.mapIndexed { i, step -> if (i == index) updated else step })
        publish()
    }

    /** The step at [index] as it stands. */
    fun step(index: Int): StepProgress = progress.steps[index]
}
