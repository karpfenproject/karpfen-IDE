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

import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.observation.ObservationMode

/**
 * Which controls of the Model pane a run offers in its session's state. They follow what the session takes: a ready or
 * running session reads the model on Pull and switches observation on and off, and a halted one reads it and switches
 * observation off only. Once the run ended, the session has read the final model and dropped the environment, so the
 * runtime keeps nothing to read, and the pane shows the model the session read last with both controls disabled. The
 * same holds while the session deploys or stops and after it failed. Stateless.
 */
internal object ModelControls {
    /** True if Pull reads the model in [state]. */
    fun pulls(state: SessionState): Boolean =
        state is SessionState.Ready || state is SessionState.Running || state is SessionState.Halted

    /** True if the observation switch, now in [mode], can be flipped in [state]. */
    fun switches(state: SessionState, mode: ObservationMode): Boolean =
        state is SessionState.Ready || state is SessionState.Running || (state is SessionState.Halted && mode == ObservationMode.ON)
}
