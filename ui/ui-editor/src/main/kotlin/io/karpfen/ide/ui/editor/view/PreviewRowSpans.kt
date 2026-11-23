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

import io.karpfen.ide.ui.editor.model.completion.CompletionPreview

/**
 * Returns the highlight spans of [row] of [preview]. [lineSpans] are the spans of the line of the preview, in columns of
 * that line. Each real run of the row keeps the syntax colours of the text it shows: the spans are cut to the columns of
 * the run and moved to where the run stands on the row. The light text of the row gets no span, so it shows without
 * syntax colours.
 */
internal fun previewRowSpans(preview: CompletionPreview, row: Int, lineSpans: List<LineSpan>): List<LineSpan> =
    preview.realRuns(row).flatMap { run ->
        val shift = run.rowColumn - run.lineColumn
        lineSpans.mapNotNull { span ->
            val start = maxOf(span.startColumn, run.lineColumn)
            val end = minOf(span.endColumn, run.lineColumn + run.length)
            if (start < end) LineSpan(start + shift, end + shift, span.style) else null
        }
    }
