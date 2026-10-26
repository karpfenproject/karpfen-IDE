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
package io.karpfen.ide.core.text

/**
 * One applied change: [before] became [after] by [edits].
 *
 * Invariants: `after.version == before.version + 1`; [edits] are non-empty, non-overlapping, sorted by range start
 * (insertions at the same offset keep their original relative order) and expressed in [before] coordinates.
 *
 * @property before the text before the change.
 * @property after the text the change produced.
 * @property edits what the change did, in the coordinates of [before].
 * @property origin why the change was made.
 */
public data class TextChange(
    public val before: TextSnapshot,
    public val after: TextSnapshot,
    public val edits: List<TextEdit>,
    public val origin: EditOrigin,
) {
    init {
        require(after.version == before.version + 1) {
            "a change advances the version by exactly one (${before.version} -> ${after.version})"
        }
        require(edits.isNotEmpty()) { "a change has at least one edit" }
    }

    /** The version this change produced. */
    public val version: Long get() = after.version
}
