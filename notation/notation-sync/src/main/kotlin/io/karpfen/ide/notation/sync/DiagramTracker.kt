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
package io.karpfen.ide.notation.sync

import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.text.TextSnapshot

/**
 * What the diagram of a model file shows at one moment.
 *
 * @param D the diagram, as it is derived from the text.
 * @property diagram the diagram, or null while the text has never been read without errors or holds nothing that
 *   makes a diagram.
 * @property isStale true while the text has syntax errors. The [diagram] then is the one of the last text that could
 *   be read, and the view marks it as outdated.
 * @property problem what went wrong if the diagram could not be derived from a text without syntax errors, or null.
 *   The [diagram] then is the last one that could be derived.
 * @property source the text the [diagram] was derived from, or null without a diagram. A change made through the
 *   diagram is an edit of exactly this text.
 */
public data class DiagramState<out D : Any>(
    public val diagram: D?,
    public val isStale: Boolean,
    public val problem: String? = null,
    public val source: TextSnapshot? = null,
) {
    public companion object {
        /** No diagram yet. */
        public val EMPTY: DiagramState<Nothing> = DiagramState(null, isStale = false)
    }
}

/**
 * Keeps the diagram of one model file in step with its text. The text is the only source. After every change of the
 * text or of its parse, [update] derives the diagram again from the text with [projection], so the diagram can never
 * hold something the text does not say.
 *
 * A diagram needs a text without syntax errors. While the text has some, the last diagram stays and is marked as
 * stale. While the parse is still behind the text, because the user is typing, the last diagram stays as it is, and
 * the next parse brings it up to date.
 *
 * A projection that fails must not take its caller down, which is a view. So a failure of the projection is caught.
 * The last diagram stays, and the state names the problem.
 *
 * One tracker belongs to one document. It is not thread-safe.
 *
 * @param D the diagram, as it is derived from the text.
 * @property projection derives the diagram from a text and its outline. It gives null for a text that holds nothing
 *   that makes a diagram.
 */
public class DiagramTracker<D : Any>(private val projection: DiagramProjection<D>) {

    private var last: D? = null
    private var source: TextSnapshot? = null

    /**
     * The state of the diagram for the text [snapshot] and the newest parse [outcome] of the document, which is null
     * before the first parse.
     */
    public fun update(snapshot: TextSnapshot, outcome: ParseOutcome?): DiagramState<D> {
        if (outcome == null || outcome.version != snapshot.version) return DiagramState(last, isStale = false, source = source)
        if (!outcome.isSyntaxClean) return DiagramState(last, isStale = true, source = source)
        val derived = try {
            projection.of(snapshot.text, outcome.outline)
        } catch (e: Exception) {
            return DiagramState(last, isStale = false, problem = describe(e), source = source)
        } catch (e: StackOverflowError) {
            return DiagramState(last, isStale = false, problem = describe(e), source = source)
        }
        last = derived
        source = snapshot.takeIf { derived != null }
        return DiagramState(last, isStale = false, source = source)
    }

    /** [error] in a few words: its kind, and its message if it has one. */
    private fun describe(error: Throwable): String {
        val kind = error::class.simpleName ?: "error"
        return error.message?.takeIf { it.isNotBlank() }?.let { "$kind, $it" } ?: kind
    }
}
