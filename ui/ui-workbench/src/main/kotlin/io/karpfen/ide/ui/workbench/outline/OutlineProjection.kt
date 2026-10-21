package io.karpfen.ide.ui.workbench.outline

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path

/**
 * Projects the parse outcomes of one document onto what the Outline shows (spec §8): the latest parse while it is free
 * of syntax diagnostics, otherwise the last good parse (or the broken parse when there never was a good one), and the
 * chain of nodes containing the caret. The nodes keep the ranges of the parse they come from (navigation maps them,
 * see [OutlineUiState.locationOf]); for the highlight they are taken in the caret's text from [OutlineRanges], which
 * keeps them anchored at the previous projection, so a projection's cost does not grow with the edits since the last
 * good parse. One per binding (the anchors are its state); used by the binding's collector only.
 */
internal class OutlineProjection(private val path: Path, text: EditableText) {

    private val ranges = OutlineRanges(text)

    /** The outline of [parse] / [lastGoodParse] for the text [snapshot] with the caret at [caret]. */
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

    private fun chooseOutcome(parse: ParseOutcome?, lastGoodParse: ParseOutcome?): ParseOutcome? =
        if (parse != null && parse.syntaxDiagnostics.isEmpty()) parse else lastGoodParse ?: parse

    /** The chain of nodes whose range in the caret's text ([rangeOf]) contains [offset], outermost first. */
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
