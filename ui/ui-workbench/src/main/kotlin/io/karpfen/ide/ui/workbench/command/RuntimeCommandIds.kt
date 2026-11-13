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
package io.karpfen.ide.ui.workbench.command

/**
 * Ids of the runtime commands that `WorkbenchState.registerCommands` registers. The application's Run menu refers to
 * them. The command that shows or hides the Runtime tool window is an ordinary tool window toggle of
 * `WorkbenchCommandIds`.
 */
public object RuntimeCommandIds {
    /**
     * Run in the Run menu. It deploys the environment descriptor of the workspace that the active editor or the user
     * picks.
     */
    public const val RUN: String = "runtime.run"

    /** Stop in the Run menu. It stops the selected run, or the only run that can be stopped. */
    public const val STOP: String = "runtime.stop"

    /**
     * Validate Project in the Run menu. It validates the whole open project, with the checks across files, and shows
     * the report in a dialog.
     */
    public const val VALIDATE: String = "runtime.validate"

    /** Pause in the Run menu. It pauses the selected run through its debugger. */
    public const val PAUSE: String = "runtime.pause"

    /** Resume in the Run menu. It lets the paused selected run run freely again. */
    public const val RESUME: String = "runtime.resume"

    /** Step in the Run menu. It runs the paused selected run to the next breakpoint or to the end of the tick. */
    public const val STEP: String = "runtime.step"

    /** Start runtime in the Run menu. It starts the runtime of the workspace without deploying anything. */
    public const val START_RUNTIME: String = "runtime.startRuntime"

    /** Stop runtime in the Run menu. It stops every live run and then the runtime. */
    public const val STOP_RUNTIME: String = "runtime.stopRuntime"

    /** Configure Bridge in the Run menu. It opens the form of a bridge file of the workspace, or of a new one. */
    public const val CONFIGURE_BRIDGE: String = "runtime.configureBridge"
}
