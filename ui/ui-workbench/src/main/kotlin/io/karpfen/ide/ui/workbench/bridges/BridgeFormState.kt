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

import io.karpfen.ide.bridges.mapping.BridgeConfigFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * The form that configures one bridge file. It holds the name of the bridge and the [draft] the user edits, and says
 * what keeps them from being saved. The workbench shows at most one form at a time, over everything else, and saves
 * or closes it through `WorkbenchState.saveBridgeForm` and `WorkbenchState.closeBridgeForm`.
 *
 * The name of a bridge is the name of its file without the suffix `.kbridge.json`. Changing it in the form renames
 * the file when the form is saved.
 *
 * Use the class from the UI thread only.
 *
 * @property directory the folder of the bridge file.
 * @property original the bridge file the form was opened for, or null for a new bridge, which has no file on disk
 *   until the form is saved.
 * @property fileProblems what was wrong with the file when the form read it, each in one sentence. The form leaves
 *   out what it could not read, so saving writes a file without these problems.
 * @param initial what the file holds, as far as it could be read.
 * @property loadedText the text of the file when the form read it, or null for a new bridge. Saving compares it with
 *   the file to notice a change that another tool made meanwhile.
 * @property suggestions the names of the project that the form offers to pick, as they were when the form opened.
 * @param taken the names of the other bridges in [directory], which this bridge cannot take.
 */
public class BridgeFormState internal constructor(
    public val directory: Path,
    public val original: Path?,
    public val fileProblems: List<String>,
    initial: BridgeDraft,
    internal val loadedText: String?,
    public val suggestions: BridgeSuggestions = BridgeSuggestions(),
    taken: Set<String> = emptySet(),
) {
    private val taken = taken.mapTo(HashSet()) { it.lowercase() }
    private val named = MutableStateFlow(original?.let(::nameOf).orEmpty())
    private val current = MutableStateFlow(initial)
    private val found = MutableStateFlow(problemsOf(named.value, initial))

    /** True if the form creates the file. */
    public val isNew: Boolean get() = original == null

    /** The name of the bridge as the form shows it, which is the name of its file without the suffix. */
    public val name: StateFlow<String> get() = named.asStateFlow()

    /** What the form shows. */
    public val draft: StateFlow<BridgeDraft> get() = current.asStateFlow()

    /** What keeps the form from being saved, each in one sentence. The form can be saved while this is empty. */
    public val problems: StateFlow<List<String>> get() = found.asStateFlow()

    /** The file that saving writes: the file named [name] in [directory]. It differs from [original] after a rename. */
    public val file: Path get() = directory.resolve(named.value.trim() + BridgeConfigFile.SUFFIX)

    /** Names the bridge [name]. */
    public fun rename(name: String) {
        named.value = name
        found.value = problemsOf(name, current.value)
    }

    /** Replaces the draft by what [change] makes of it. */
    public fun edit(change: (BridgeDraft) -> BridgeDraft) {
        val next = change(current.value)
        current.value = next
        found.value = problemsOf(named.value, next)
    }

    /** What is wrong with [name], followed by what is wrong with [draft]. */
    private fun problemsOf(name: String, draft: BridgeDraft): List<String> {
        val trimmed = name.trim()
        val nameProblem = when {
            trimmed.isEmpty() -> "The bridge needs a name."
            trimmed.any { it == '/' || it == '\\' } -> "The name of the bridge must not contain a slash."
            trimmed.lowercase() in taken -> "Another bridge is named \"$trimmed\" already."
            else -> null
        }
        return listOfNotNull(nameProblem) + BridgeDrafts.problemsOf(draft)
    }

    public companion object {
        /** The name of the bridge in [file], which is the name of the file without the suffix of bridge files. */
        public fun nameOf(file: Path): String {
            val fileName = file.fileName.toString()
            return if (fileName.endsWith(BridgeConfigFile.SUFFIX, ignoreCase = true)) fileName.dropLast(BridgeConfigFile.SUFFIX.length) else fileName
        }
    }
}
