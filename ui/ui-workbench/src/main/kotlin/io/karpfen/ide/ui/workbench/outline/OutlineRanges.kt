package io.karpfen.ide.ui.workbench.outline

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.util.IdentityHashMap

/**
 * The ranges of an outline's nodes in the current text, anchored at the latest projected version (like the session's
 * external diagnostics, decision 50 of the work-package plan): when the outlined parse changes, every node's range is
 * mapped once from the parse's version; for each later text version the anchored ranges are mapped on from the
 * previous one only, with [EditableText.mapRange] (the one range-mapping rule, decision 35). So a projection maps
 * across the versions since the previous projection — one keystroke, usually — instead of across every edit since the
 * last good parse, which made each keystroke of a document with a syntax error more expensive than the one before and
 * lost the highlight once the text no longer retained those edits. A range that cannot be mapped is dropped until the
 * parse changes.
 *
 * Mutable, for one binding: used by its collector only.
 */
internal class OutlineRanges(private val text: EditableText) {
    private var source: ParseOutcome? = null
    private var lineage: TextLineage? = null
    private var version = -1L

    /** The node ranges at [version]; null while that is the source's own version (its ranges as parsed). */
    private var anchored: Map<OutlineNode, TextRange>? = null

    /** Each node's range of [source]'s outline in the text of [snapshot]; null for a node that cannot be mapped there. */
    fun at(source: ParseOutcome, snapshot: TextSnapshot): (OutlineNode) -> TextRange? {
        if (snapshot.version < source.version) {
            this.source = null // a parse ahead of the text it is projected onto: nothing maps back, anchor later
            return { null }
        }
        when {
            source !== this.source || snapshot.lineage !== lineage || snapshot.version < version -> anchor(source, snapshot)
            snapshot.version != version -> advance(source, snapshot)
        }
        val ranges = anchored ?: return OutlineNode::range
        return { node -> ranges[node] }
    }

    private fun anchor(source: ParseOutcome, snapshot: TextSnapshot) {
        anchored = if (source.version == snapshot.version) {
            null
        } else {
            mapNodes(source.outline) { node -> text.mapRange(node.range, source.version, snapshot.lineage, snapshot.version) }
        }
        remember(source, snapshot)
    }

    private fun advance(source: ParseOutcome, snapshot: TextSnapshot) {
        val previous = anchored
        val from = version
        anchored = mapNodes(source.outline) { node ->
            val range = if (previous == null) node.range else previous[node]
            range?.let { text.mapRange(it, from, snapshot.lineage, snapshot.version) }
        }
        remember(source, snapshot)
    }

    private fun remember(source: ParseOutcome, snapshot: TextSnapshot) {
        this.source = source
        lineage = snapshot.lineage
        version = snapshot.version
    }

    /** The ranges [map] gives the nodes of [outline] (all levels; identity keys: equal nodes may repeat). */
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
