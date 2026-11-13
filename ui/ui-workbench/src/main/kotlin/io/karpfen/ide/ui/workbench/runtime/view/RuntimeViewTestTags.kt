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
package io.karpfen.ide.ui.workbench.runtime.view

import io.karpfen.ide.runtime.host.RuntimeHostKind

/** Test tags of the Runtime tool window. */
public object RuntimeViewTestTags {
    /** The Model pane's Expand all button. */
    public const val EXPAND_ALL: String = "Runtime.model.expandAll"

    /** The Model pane's Collapse all button. */
    public const val COLLAPSE_ALL: String = "Runtime.model.collapseAll"

    /** The Log pane's Simplified toggle. */
    public const val SIMPLIFIED: String = "Runtime.log.simplified"

    /** The line about the deployment in the Log pane. A click opens and closes the details. */
    public const val DEPLOYMENT: String = "Runtime.log.deployment"

    /** The line above the log that names the file the run is captured to. */
    public const val CAPTURE: String = "Runtime.log.capture"

    /** The explanation of the event form. */
    public const val SEND_HELP: String = "Runtime.injector.help"

    /** What the Events pane says while no event was seen. */
    public const val NO_EVENTS: String = "Runtime.events.none"

    /** The host's status in words in the header. */
    public const val HOST_STATUS: String = "Runtime.header.status"

    /** The header's Start runtime button. */
    public const val START_RUNTIME: String = "Runtime.header.start"

    /** The header's Stop runtime button. */
    public const val STOP_RUNTIME: String = "Runtime.header.stop"

    /** The URL field of the host selector. */
    public const val URL_FIELD: String = "Runtime.header.url"

    /** The button that applies the URL field. */
    public const val URL_APPLY: String = "Runtime.header.urlApply"

    /** The empty tool window's explanation of how to run. */
    public const val EMPTY: String = "Runtime.empty"

    /** The debugger's line of the selected run. */
    public const val DEBUGGER: String = "Runtime.debugger"

    /** The debugger's words for where the selected run is paused. */
    public const val PAUSE_PLACE: String = "Runtime.debugger.place"

    /** The debugger's Pause button. */
    public const val PAUSE: String = "Runtime.debugger.pause"

    /** The debugger's Resume button. */
    public const val RESUME: String = "Runtime.debugger.resume"

    /** The debugger's Step button. */
    public const val STEP: String = "Runtime.debugger.step"

    /** The Run again button of the selected run. */
    public const val RERUN: String = "Runtime.runs.rerun"

    /** The Stop button of the selected run. */
    public const val STOP: String = "Runtime.runs.stop"

    /** The Clear finished button. */
    public const val CLEAR_FINISHED: String = "Runtime.runs.clearFinished"

    /** The observation switch of the Model tab. */
    public const val OBSERVATION: String = "Runtime.model.observation"

    /** The Pull button of the Model tab. */
    public const val PULL: String = "Runtime.model.pull"

    /** The status line of the Model tab. */
    public const val MODEL_STATUS: String = "Runtime.model.status"

    /** The payload editor of the injector. */
    public const val PAYLOAD: String = "Runtime.injector.payload"

    /** The Send button of the injector. */
    public const val SEND: String = "Runtime.injector.send"

    /** The injector's availability. */
    public const val AVAILABILITY: String = "Runtime.injector.availability"

    /** The outcome of the last send. */
    public const val OUTCOME: String = "Runtime.injector.outcome"

    /** The full payload of the event chosen in the event log. */
    public const val EVENT_DETAIL: String = "Runtime.events.detail"

    /** The option of the host selector for [kind]. */
    public fun host(kind: RuntimeHostKind): String = "Runtime.header.host:$kind"

    /** The row of the run [id] in the runs list. */
    public fun run(id: Long): String = "Runtime.runs.run:$id"

    /** The phase label of the run [id]. */
    public fun phase(id: Long): String = "Runtime.runs.phase:$id"

    /** The finding at [index] in the selected run's Log pane. */
    public fun finding(index: Int): String = "Runtime.overview.finding:$index"

    /** The line of the bridge at [index] in the selected run's Log pane. */
    public fun bridge(index: Int): String = "Runtime.overview.bridge:$index"

    /** The active states of the machine attached to [key]. */
    public fun activeStates(key: String): String = "Runtime.overview.states:$key"

    /** The row [key] of the model tree. */
    public fun modelRow(key: String): String = "Runtime.model.row:$key"

    /** The link to the object [id] in the model tree. */
    public fun link(id: String): String = "Runtime.model.link:$id"

    /** The entry of the event log with the sequence number [sequence]. */
    public fun event(sequence: Long): String = "Runtime.events.event:$sequence"

    /** The injector's option for the domain [name]. */
    public fun domain(name: String): String = "Runtime.injector.domain:$name"

    /** The injector's option for the event [name]. */
    public fun injectable(name: String): String = "Runtime.injector.event:$name"

    /** The finding at [index] on the injector's payload. */
    public fun diagnostic(index: Int): String = "Runtime.injector.diagnostic:$index"
}
