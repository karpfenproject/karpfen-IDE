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
package io.karpfen.ide.ui.workbench.runtime

import java.nio.file.Path

/**
 * How a descriptor is run: which bridges start with it, whether it is captured to a file and whether it brings a
 * debugger along. In the window the user makes the choice in the run dialog, and a run there always has the debugger
 * the project asks for. A caller that runs without a dialog, through `RuntimeState.run` with a choice, makes it
 * itself.
 *
 * @property bridges the bridge files to start with the run, which may be none.
 * @property capture whether the runtime's log of the run is captured to a file in the project.
 * @property debugger whether the deployment switches on the runtime's feature `Breakpoint` when the project has
 *   breakpoints switched on, so the run halts at them and offers a debugger. Without it the run never halts, and the
 *   breakpoints in the texts stay allowed or forbidden as the project says.
 */
public data class RunChoice(
    public val bridges: List<Path>,
    public val capture: Boolean,
    public val debugger: Boolean = true,
)
