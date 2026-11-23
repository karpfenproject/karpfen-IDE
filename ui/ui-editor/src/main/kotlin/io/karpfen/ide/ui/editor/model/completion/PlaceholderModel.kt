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

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The placeholders of the template that was inserted last in one editor. It is a state holder without Compose, used from
 * the UI thread, next to the [CompletionSessionModel] that inserts the templates.
 *
 * After [start] the first placeholder is selected, so typing replaces it. Tab selects the placeholder after the one the
 * caret stands in, or after the one Tab selected last while the caret stands in none, and Shift+Tab the one before. Tab
 * on the last placeholder puts the caret at the end position of the template and ends the placeholders. The
 * placeholders follow the edits made while they are filled in, as [PlaceholderSession] describes.
 *
 * The placeholders end on Escape, through [end], when the primary caret is put outside the template, when an edit
 * changes the text outside it or at its bounds, when the text of every placeholder was deleted, when undo removes the
 * template, and when a newer template is inserted. The editor ends
 * them when it closes. Only the primary caret counts, and equal placeholders are not linked.
 */
internal interface PlaceholderModel {

    /** True while the placeholders of a template are active. Asking it checks them against the text and the caret. */
    val isActive: Boolean

    /** Activates the placeholders of [insertion], which was just inserted, and ends any earlier ones. */
    fun start(insertion: TemplateInsertion)

    /**
     * Handles Tab, Shift+Tab and Escape while placeholders are active. Returns true if the key was consumed, and then
     * the editor does not run its own binding. Always returns false while no placeholders are active, so Tab indents
     * again. Every other key, Enter included, is left to the editor.
     */
    fun handleKey(stroke: KeyStroke): Boolean

    /** Ends the placeholders. Does nothing if none are active. */
    fun end()
}

/**
 * Creates a [PlaceholderModel] for [text] and [caret]. It observes the caret in [scope], which is the UI scope of the
 * editor, so the placeholders end as soon as the caret leaves the template.
 */
internal fun PlaceholderModel(text: EditableText, caret: CaretModel, scope: CoroutineScope): PlaceholderModel =
    DefaultPlaceholderModel(text, caret, scope)

/**
 * The default [PlaceholderModel]. It keeps the current [PlaceholderSession] and brings it up to date on every question:
 * it maps the session into the current text and drops it when the text or the caret says so. The caret is observed in
 * [scope] as well, so a click or a movement outside the template ends the placeholders even when no key follows.
 */
internal class DefaultPlaceholderModel(
    private val text: EditableText,
    private val caret: CaretModel,
    scope: CoroutineScope,
) : PlaceholderModel {

    private var session: PlaceholderSession? = null

    init {
        scope.launch { caret.state.collect { live() } }
    }

    override val isActive: Boolean get() = live() != null

    override fun start(insertion: TemplateInsertion) {
        session = PlaceholderSession.of(insertion)
        live()
    }

    override fun handleKey(stroke: KeyStroke): Boolean {
        val live = live() ?: return false
        when {
            stroke.key == KeyCode.TAB && stroke.modifiers.isEmpty() -> next(live)
            stroke.key == KeyCode.TAB && stroke.modifiers == setOf(KeyModifier.SHIFT) -> select(live.at(live.index - 1))
            stroke.key == KeyCode.ESCAPE && stroke.modifiers.isEmpty() -> end()
            else -> return false
        }
        return true
    }

    override fun end() {
        session = null
    }

    /** Selects the placeholder after the current one of [live], or ends the placeholders at the end of the template. */
    private fun next(live: PlaceholderSession) {
        if (live.index < live.placeholders.lastIndex) {
            select(live.at(live.index + 1))
        } else {
            end()
            caret.moveTo(live.end)
        }
    }

    /** Makes [next] the session and selects its current placeholder. */
    private fun select(next: PlaceholderSession) {
        session = next
        caret.select(next.current.start, next.current.end)
    }

    /**
     * The session mapped into the current text, or null after it ended because of the text or the primary caret. Its
     * current placeholder is the one the primary caret stands in, so Tab moves on from there.
     */
    private fun live(): PlaceholderSession? {
        val primary = caret.primary
        session = session?.following(text)?.takeIf { it.contains(primary) }?.focusedOn(primary)
        return session
    }
}
