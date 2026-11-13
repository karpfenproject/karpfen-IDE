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
 * How the user chose to run in the run dialog.
 *
 * @property bridges the bridge files to start with the run, which may be none.
 * @property capture whether the runtime's log of the run is captured to a file in the project.
 */
internal data class RunChoice(val bridges: List<Path>, val capture: Boolean)
