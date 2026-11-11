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
package io.karpfen.ide.runtime.session.problem

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path

/**
 * Where a deploy problem points. That is a file of the workspace and, if known, a range of the text that was analyzed
 * and uploaded. Immutable.
 *
 * @property file the absolute path of the file.
 * @property range the range in [snapshot] the problem concerns, or null for the file as a whole.
 * @property snapshot the text that [range] refers to, which is the analyzed text the deployment uploads. It is null if
 *   no text of the file was analyzed, as for an environment descriptor.
 */
public data class SourceLocation(
    public val file: Path,
    public val range: TextRange?,
    public val snapshot: TextSnapshot?,
) {
    init {
        require(range == null || snapshot == null || range.end <= snapshot.length) {
            "the range $range lies outside the text of $file (length ${snapshot?.length})"
        }
    }
}
