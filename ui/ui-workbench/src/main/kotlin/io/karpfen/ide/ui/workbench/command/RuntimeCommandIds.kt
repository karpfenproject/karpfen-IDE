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
 * Ids of the runtime commands the workbench registers (`WorkbenchState.registerCommands`), referenced by the
 * application's Run menu. The Runtime tool window's toggle is a tool-window toggle of `WorkbenchCommandIds`.
 */
public object RuntimeCommandIds {
    /** Run menu: Run, which deploys the environment descriptor of the workspace that the active editor or the user picks. */
    public const val RUN: String = "runtime.run"

    /** Run menu: Stop of the selected run, or of the only run that can be stopped. */
    public const val STOP: String = "runtime.stop"

    /** Run menu: Start runtime, which starts the runtime of the workspace without deploying anything. */
    public const val START_RUNTIME: String = "runtime.startRuntime"

    /** Run menu: Stop runtime, which stops every live run and then the runtime. */
    public const val STOP_RUNTIME: String = "runtime.stopRuntime"
}
