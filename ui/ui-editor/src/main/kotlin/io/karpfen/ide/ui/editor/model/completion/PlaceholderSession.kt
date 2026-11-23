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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.Caret

/**
 * The active placeholders of one inserted template, stamped with the text [stamp] they were last checked against. It
 * is an immutable value. The [PlaceholderModel] keeps the current one and replaces it on every check.
 *
 * The session follows the text through [following] the way `AutoClosedPairs` and the carets do: lazily, by mapping
 * its offsets with `EditableText.mapOffset` from the stamp to the current version. So it needs no subscription to the
 * changes, and it is current whenever it is asked, also when a change has not reached any collector yet.
 *
 * The template itself shrinks like a decoration: its start maps with `Bias.RIGHT` and its end with `Bias.LEFT`, so text
 * typed right before or right behind the template lies outside it and ends the placeholders. A placeholder grows
 * instead: its start maps with `Bias.LEFT` and its end with `Bias.RIGHT`, so text typed at either bound of a
 * placeholder, or typed over a selected placeholder, belongs to it. A placeholder that was emptied stays as an empty
 * range at its place. A placeholder whose neighbouring characters were deleted, as when the line it stands on is
 * deleted, is gone and is dropped, so Tab never stops at a place that no longer holds it. The placeholders end once
 * none is left.
 *
 * @property stamp the text the offsets belong to.
 * @property region the whole inserted template. Edits within it keep the session.
 * @property placeholders the placeholders in visiting order.
 * @property end where the caret goes after the last placeholder.
 * @property index the current placeholder, the one that Tab and Shift+Tab move on from.
 * @property before the text before the template was inserted. It is the text that undoing the insertion brings back.
 */
internal data class PlaceholderSession(
    val stamp: TextSnapshot,
    val region: TextRange,
    val placeholders: List<TextRange>,
    val end: Int,
    val index: Int,
    val before: TextSnapshot,
) {
    init {
        require(index in placeholders.indices) { "index $index lies outside the ${placeholders.size} placeholders" }
    }

    /** The placeholder at [index]. */
    val current: TextRange get() = placeholders[index]

    /** The same session with the placeholder at [index] as the current one. */
    fun at(index: Int): PlaceholderSession = copy(index = index.coerceIn(placeholders.indices))

    /** True if both ends of [caret] lie in the template, its bounds included. */
    fun contains(caret: Caret): Boolean = region.containsInclusive(caret.anchor) && region.containsInclusive(caret.position)

    /**
     * The same session with the placeholder that holds the selection of [caret] as the current one, so Tab moves on
     * from where the caret is and not from where Tab put it last. The current placeholder stays current while it holds
     * the selection, and also when no placeholder does.
     */
    fun focusedOn(caret: Caret): PlaceholderSession {
        val selection = caret.selection
        val holds = { range: TextRange -> range.start <= selection.start && selection.end <= range.end }
        if (holds(current)) return this
        val holder = placeholders.indexOfFirst(holds)
        return if (holder < 0) this else copy(index = holder)
    }

    /**
     * The session mapped into the current snapshot of [text], or null if the placeholders end with the changes since
     * [stamp]. They end when the text is again the text [before] the insertion, which is what undoing the insertion
     * does, when an edit changed the text before or after the template, its bounds included, when no placeholder is
     * left, and when the history no longer reaches [stamp]. The text outside the template is compared with the stamp,
     * so an edit outside counts even when it adds as many characters as it removes. When the current placeholder is
     * dropped, the one before it becomes current, so the next Tab selects the first placeholder after it that is left.
     */
    fun following(text: EditableText): PlaceholderSession? {
        val current = text.snapshot.value
        if (current.version == stamp.version) return this
        if (current.version < stamp.version || current.lineage !== stamp.lineage) return null
        if (current.length == before.length && current.text == before.text) return null
        val map = { offset: Int, bias: Bias -> text.mapOffset(offset, stamp.version, current.version, bias) }
        val start = map(region.start, Bias.RIGHT) ?: return null
        val stop = map(region.end, Bias.LEFT) ?: return null
        if (stop < start || !sameOutside(current, start, stop)) return null
        val kept = ArrayList<TextRange>(placeholders.size)
        var keptBefore = 0
        var currentKept = false
        for ((i, range) in placeholders.withIndex()) {
            if (!survives(text, range)) continue
            val from = map(range.start, Bias.LEFT) ?: return null
            val to = map(range.end, Bias.RIGHT) ?: return null
            if (i < index) keptBefore++
            if (i == index) currentKept = true
            kept += if (from <= to) TextRange(from, to) else TextRange.empty(to)
        }
        if (kept.isEmpty()) return null
        val mappedEnd = map(end, Bias.RIGHT) ?: return null
        return copy(
            stamp = current,
            region = TextRange(start, stop),
            placeholders = kept,
            end = mappedEnd.coerceIn(start, stop),
            index = (if (currentKept) keptBefore else keptBefore - 1).coerceIn(kept.indices),
        )
    }

    /**
     * True if the characters right before and right behind [range] in [stamp] are still in the current [text]. They
     * are the quotes or brackets around a placeholder. Emptying a placeholder keeps them, and deleting the text around
     * it removes them.
     */
    private fun survives(text: EditableText, range: TextRange): Boolean {
        val stays = { offset: Int -> text.mapRange(TextRange.ofLength(offset, 1), stamp)?.isEmpty == false }
        return (range.start == 0 || stays(range.start - 1)) && (range.end == stamp.length || stays(range.end))
    }

    /** True if [current] holds the text of [stamp] before [start] and after [stop], where the template now lies. */
    private fun sameOutside(current: TextSnapshot, start: Int, stop: Int): Boolean {
        val after = stamp.length - region.end
        return start == region.start &&
            current.length - stop == after &&
            current.text.regionMatches(0, stamp.text, 0, start) &&
            current.text.regionMatches(stop, stamp.text, region.end, after)
    }

    /** Creates sessions. */
    companion object {
        /** The session of a template that was just inserted, with its first placeholder current. */
        fun of(insertion: TemplateInsertion): PlaceholderSession =
            PlaceholderSession(insertion.after, insertion.inserted, insertion.placeholders, insertion.end, 0, insertion.before)
    }
}
