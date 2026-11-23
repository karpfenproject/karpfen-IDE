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

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * A template that the completion model has just inserted. The completion model reports it to the listener passed to
 * its factory, which in the editor starts the placeholders of the template. It is an immutable value, and every offset
 * belongs to [after].
 *
 * @property before the text right before the insertion. Undoing the insertion brings exactly this text back.
 * @property after the text right after the insertion.
 * @property inserted the inserted text.
 * @property placeholders the placeholders of the template in visiting order. There is at least one.
 * @property end where the caret goes after the last placeholder.
 */
public data class TemplateInsertion(
    public val before: TextSnapshot,
    public val after: TextSnapshot,
    public val inserted: TextRange,
    public val placeholders: List<TextRange>,
    public val end: Int,
) {
    init {
        require(placeholders.isNotEmpty()) { "a template has at least one placeholder" }
        require(placeholders.all { it in inserted } && end in inserted.start..inserted.end) {
            "the placeholders $placeholders and the end $end lie within the insertion $inserted"
        }
    }
}
