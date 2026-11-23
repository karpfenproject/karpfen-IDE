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
package io.karpfen.ide.ui.editor.view

import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.ui.editor.model.completion.CompletionPreview
import kotlin.test.Test
import kotlin.test.assertEquals

/** The syntax colours of the rows of a completion preview: real runs keep them, light text has none. */
class PreviewRowSpansTest {

    /** The preview of `  TRA x` with `TRA` replaced by `TRANSITION {` and `}`, as the acceptance computes it. */
    private val preview = CompletionPreview(
        version = 0,
        line = 0,
        lineStart = 0,
        rows = listOf("  TRANSITION {", "  } x"),
        realColumns = 5,
        tailOffset = 5,
        tailColumn = 3,
    )

    /** The spans of the line `  TRA x`: the keyword `TRA` and the identifier `x`. */
    private val lineSpans = listOf(LineSpan(2, 5, HighlightStyle.KEYWORD), LineSpan(6, 7, HighlightStyle.IDENTIFIER))

    @Test
    fun `the real prefix keeps its colours and the light text gets none`() {
        assertEquals(listOf(LineSpan(2, 5, HighlightStyle.KEYWORD)), previewRowSpans(preview, 0, lineSpans))
    }

    @Test
    fun `the tail keeps its colours where it is drawn`() {
        assertEquals(listOf(LineSpan(4, 5, HighlightStyle.IDENTIFIER)), previewRowSpans(preview, 1, lineSpans))
    }

    @Test
    fun `a span across the end of the real prefix is cut there`() {
        val wide = listOf(LineSpan(0, 7, HighlightStyle.COMMENT))

        assertEquals(listOf(LineSpan(0, 5, HighlightStyle.COMMENT)), previewRowSpans(preview, 0, wide))
        assertEquals(listOf(LineSpan(3, 5, HighlightStyle.COMMENT)), previewRowSpans(preview, 1, wide))
    }
}
