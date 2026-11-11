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

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.runtime.session.problem.DeployProblem

/**
 * Where the editor opens this deploy problem. That is its file at its range, or the start of the file when the problem
 * has no range. When the problem names a text snapshot, the location carries it, so navigation can map the range into
 * the live text. Returns null for a problem without a file.
 */
public fun DeployProblem.navigationTarget(): Location? {
    val location = location ?: return null
    val range = location.range ?: TextRange(0, 0)
    return location.snapshot?.let { Location(location.file, range, it) } ?: Location(location.file, range)
}
