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
package io.karpfen.ide.ui.workbench.bridges

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * The form that configures one bridge file. It holds the [draft] the user edits and says what keeps it from being
 * saved. The workbench shows at most one form at a time, over everything else, and saves or closes it through
 * `WorkbenchState.saveBridgeForm` and `WorkbenchState.closeBridgeForm`.
 *
 * Use the class from the UI thread only.
 *
 * @property file the bridge file. A new bridge has no file on disk until the form is saved.
 * @property name the file relative to the project folder, which names the bridge to the user.
 * @property isNew true if the form creates the file.
 * @property fileProblems what was wrong with the file when the form read it, each in one sentence. The form leaves
 *   out what it could not read, so saving writes a file without these problems.
 * @param initial what the file holds, as far as it could be read.
 * @property loadedText the text of the file when the form read it, or null for a new bridge. Saving compares it with
 *   the file to notice a change that another tool made meanwhile.
 * @property suggestions the names of the project that the form offers to pick, as they were when the form opened.
 */
public class BridgeFormState internal constructor(
    public val file: Path,
    public val name: String,
    public val isNew: Boolean,
    public val fileProblems: List<String>,
    initial: BridgeDraft,
    internal val loadedText: String?,
    public val suggestions: BridgeSuggestions = BridgeSuggestions(),
) {
    private val current = MutableStateFlow(initial)
    private val found = MutableStateFlow(BridgeDrafts.problemsOf(initial))

    /** What the form shows. */
    public val draft: StateFlow<BridgeDraft> get() = current.asStateFlow()

    /** What keeps the draft from being saved, each in one sentence. The form can be saved while this is empty. */
    public val problems: StateFlow<List<String>> get() = found.asStateFlow()

    /** Replaces the draft by what [change] makes of it. */
    public fun edit(change: (BridgeDraft) -> BridgeDraft) {
        val next = change(current.value)
        current.value = next
        found.value = BridgeDrafts.problemsOf(next)
    }
}
