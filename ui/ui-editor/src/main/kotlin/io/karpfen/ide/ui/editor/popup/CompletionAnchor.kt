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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.geometry.Rect

/**
 * Where the completion list is anchored: at [caretLine], the caret reaching down to the last row of a completion
 * preview, and at [caret], the caret alone, when the preview leaves too little room. Below [caretLine] the list does
 * not cover the preview. An immutable value, so the completion slot can keep one while the pointer rests on the list.
 *
 * @property caret the rectangle of the primary caret, one row tall.
 * @property caretLine the rectangle of the primary caret reaching down to the last row that shows the caret's line.
 */
internal data class CompletionAnchor(val caret: Rect, val caretLine: Rect) {

    /**
     * Returns the anchor of a list [listHeight] pixels high in an area [areaHeight] pixels high. That is [caretLine]
     * when the list fits below or above it, and [caret] when that leaves more room. So a preview taller than the room
     * never squeezes the list to nothing while it takes the keys.
     */
    fun resolve(listHeight: Int, areaHeight: Int): Rect = PopupPlacement.anchorFor(caretLine, caret, listHeight, areaHeight)
}
