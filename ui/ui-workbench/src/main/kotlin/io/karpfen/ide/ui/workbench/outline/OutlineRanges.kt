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
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.util.IdentityHashMap

/**
 * The ranges of an outline's nodes in the current text. They are anchored at the latest projected version, the same
 * way a document session anchors its external diagnostics.
 *
 * When the outlined parse changes, the range of every node is mapped once from the parse's version. For each later
 * text version, the anchored ranges are mapped on from the previous version only. The mapping always goes through
 * [EditableText.mapRange], which is the single rule for mapping ranges between versions.
 *
 * A projection therefore maps across the versions since the previous projection, which is usually one keystroke.
 * Mapping across every edit since the last good parse instead would make each keystroke in a document with a syntax
 * error more expensive than the one before. It would also lose the highlight once the text no longer keeps those
 * edits. A range that cannot be mapped is dropped until the parse changes.
 *
 * The class is mutable and belongs to one editor binding. Only that binding's collector uses it.
 */
internal class OutlineRanges(private val text: EditableText) {
    private var source: ParseOutcome? = null
    private var lineage: TextLineage? = null
    private var version = -1L

    /**
     * The node ranges at [version]. Null while [version] is the source's own version, because the ranges are then
     * those of the parse itself.
     */
    private var anchored: Map<OutlineNode, TextRange>? = null

    /**
     * Returns a lookup from each node of the outline of [source] to its range in the text of [snapshot]. The lookup
     * returns null for a node that cannot be mapped there. A parse can be ahead of the text it is projected onto, when
     * [snapshot] is older than [source]. Nothing is mapped back in that case. Every node then answers null, and the
     * ranges are anchored afresh on the next call.
     */
    fun at(source: ParseOutcome, snapshot: TextSnapshot): (OutlineNode) -> TextRange? {
        if (snapshot.version < source.version) {
            this.source = null
            return { null }
        }
        when {
            source !== this.source || snapshot.lineage !== lineage || snapshot.version < version -> anchor(source, snapshot)
            snapshot.version != version -> advance(source, snapshot)
        }
        val ranges = anchored ?: return OutlineNode::range
        return { node -> ranges[node] }
    }

    /**
     * Maps the range of every node from the version of [source] into [snapshot]. When both versions agree, the ranges
     * stay as parsed.
     */
    private fun anchor(source: ParseOutcome, snapshot: TextSnapshot) {
        anchored = if (source.version == snapshot.version) {
            null
        } else {
            mapNodes(source.outline) { node -> text.mapRange(node.range, source.version, snapshot.lineage, snapshot.version) }
        }
        remember(source, snapshot)
    }

    /** Maps the anchored ranges on from the previously projected version into [snapshot]. */
    private fun advance(source: ParseOutcome, snapshot: TextSnapshot) {
        val previous = anchored
        val from = version
        anchored = mapNodes(source.outline) { node ->
            val range = if (previous == null) node.range else previous[node]
            range?.let { text.mapRange(it, from, snapshot.lineage, snapshot.version) }
        }
        remember(source, snapshot)
    }

    /** Makes [source] and the lineage and version of [snapshot] the anchor of the next projection. */
    private fun remember(source: ParseOutcome, snapshot: TextSnapshot) {
        this.source = source
        lineage = snapshot.lineage
        version = snapshot.version
    }

    /**
     * The ranges that [map] gives the nodes of [outline], on all levels. The map uses identity keys, because equal
     * nodes may occur more than once.
     */
    private fun mapNodes(outline: List<OutlineNode>, map: (OutlineNode) -> TextRange?): Map<OutlineNode, TextRange> {
        val ranges = IdentityHashMap<OutlineNode, TextRange>()
        val pending = ArrayDeque(outline)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            map(node)?.let { ranges[node] = it }
            pending.addAll(node.children)
        }
        return ranges
    }
}
