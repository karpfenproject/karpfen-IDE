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
package io.karpfen.ide.ui.workbench.outline

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.ui.editor.state.EditorHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * UI state of the Outline tool window.
 *
 * @property path the outlined document, or null when nothing is outlined.
 * @property version the version of the parse that [nodes] come from. While the text has syntax errors, that is the last
 *   good parse. Navigation uses it to map the node ranges into the live text. Null when nothing is outlined.
 * @property lineage the text lineage of the document, see `TextLineage`. Null when nothing is outlined.
 * @property nodes the outline of the active document's latest parse. While the text has syntax errors, it is the
 *   outline of the last good parse.
 * @property highlighted the chain of nodes that contain the caret, innermost last. The tree highlights them.
 * @property isStale true if [nodes] come from an older version than the current text.
 */
public data class OutlineUiState(
    public val path: Path? = null,
    public val version: Long? = null,
    public val lineage: TextLineage? = null,
    public val nodes: List<OutlineNode> = emptyList(),
    public val highlighted: List<OutlineNode> = emptyList(),
    public val isStale: Boolean = false,
) {
    init {
        require(lineage == null || version != null) { "a lineage needs a version" }
    }

    /**
     * Where a click on [node] navigates to. That is the node's selection range in the parse it comes from, together
     * with the version and lineage of that parse. `WorkbenchState.navigateTo` maps it into the live text. Returns null
     * when nothing is outlined.
     */
    public fun locationOf(node: OutlineNode): Location? = path?.let { Location(it, node.selectionRange, version, lineage) }
}

/**
 * The outline of the active document. It follows the parse and the caret of the bound editor and collects them in
 * [scope]. Binding an editor is an operation of the mediator and is internal, so no view can bypass the coordinated
 * flows of the workbench. [OutlineProjection] turns a parse into an [OutlineUiState]. Each binding has its own
 * projection.
 */
public class OutlineState(private val scope: CoroutineScope) {

    private val outline = MutableStateFlow(OutlineUiState())
    private var binding: Job? = null

    /** What the Outline tool window shows. */
    public val state: StateFlow<OutlineUiState> get() = outline.asStateFlow()

    /**
     * Follows [editor], which is the editor of the active tab. The outline then tracks the parse and the last good
     * parse of its session, its text and its `caret.state`. For null the outline shows nothing. Only the mediator
     * calls it.
     */
    internal fun bindEditor(editor: EditorHandle?) {
        binding?.cancel()
        binding = null
        if (editor == null) {
            outline.value = OutlineUiState()
            return
        }
        val session = editor.session
        val projection = OutlineProjection(session.id.path, session.text)
        outline.value = projection.of(
            session.parse.value, session.lastGoodParse.value, session.text.snapshot.value, editor.caret.state.value.primary.position,
        )
        binding = scope.launch {
            combine(session.parse, session.lastGoodParse, session.text.snapshot, editor.caret.state) { parse, good, text, carets ->
                projection.of(parse, good, text, carets.primary.position)
            }.collect { outline.value = it }
        }
    }
}
