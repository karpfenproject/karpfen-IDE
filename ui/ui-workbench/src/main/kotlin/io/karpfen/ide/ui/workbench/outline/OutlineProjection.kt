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
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path

/**
 * Projects the parse outcomes of one document onto what the Outline shows.
 *
 * The Outline shows the latest parse while it is free of syntax diagnostics. Otherwise it shows the last good parse.
 * If there never was a good parse, it shows the broken one. It also highlights the chain of nodes that contain the
 * caret.
 *
 * The nodes keep the ranges of the parse they come from. Navigation maps those ranges into the live text, as
 * [OutlineUiState.locationOf] describes. The highlight needs the ranges in the caret's text instead. [OutlineRanges]
 * provides them and keeps them anchored at the previous projection, so the cost of a projection does not grow with the
 * number of edits since the last good parse.
 *
 * There is one instance per editor binding, because the anchors are its state. Only that binding's collector uses it.
 */
internal class OutlineProjection(private val path: Path, text: EditableText) {

    private val ranges = OutlineRanges(text)

    /** The outline of [parse] or [lastGoodParse] for the text [snapshot] with the caret at [caret]. */
    fun of(parse: ParseOutcome?, lastGoodParse: ParseOutcome?, snapshot: TextSnapshot, caret: Int): OutlineUiState {
        val source = chooseOutcome(parse, lastGoodParse) ?: return OutlineUiState(path = path)
        val offset = caret.coerceIn(0, snapshot.length)
        return OutlineUiState(
            path = path,
            version = source.version,
            lineage = snapshot.lineage,
            nodes = source.outline,
            highlighted = pathAt(source.outline, offset, ranges.at(source, snapshot)),
            isStale = source.version != snapshot.version,
        )
    }

    /**
     * Picks the outcome to show. That is the latest [parse] while it is free of syntax diagnostics, otherwise
     * [lastGoodParse], and otherwise the broken [parse].
     */
    private fun chooseOutcome(parse: ParseOutcome?, lastGoodParse: ParseOutcome?): ParseOutcome? =
        if (parse != null && parse.syntaxDiagnostics.isEmpty()) parse else lastGoodParse ?: parse

    /**
     * The chain of nodes whose range in the caret's text contains [offset], outermost first. [rangeOf] gives that
     * range.
     */
    private fun pathAt(nodes: List<OutlineNode>, offset: Int, rangeOf: (OutlineNode) -> TextRange?): List<OutlineNode> {
        val chain = ArrayList<OutlineNode>()
        var level = nodes
        while (true) {
            val node = level.firstOrNull { rangeOf(it)?.containsInclusive(offset) == true } ?: return chain
            chain += node
            level = node.children
        }
    }
}
