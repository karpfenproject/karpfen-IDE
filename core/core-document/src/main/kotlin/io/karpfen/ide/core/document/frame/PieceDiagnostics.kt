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
package io.karpfen.ide.core.document.frame

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextFrame
import io.karpfen.ide.core.text.TextRange

/** Carries the diagnostics of a whole text onto the piece of a [TextFrame]. */
public object PieceDiagnostics {

    /**
     * The diagnostics among [diagnostics] of the whole text of [frame] that lie in its piece, in the coordinates of the
     * piece. A diagnostic that reaches beyond the piece is clipped to it, and one outside the piece is left out,
     * because it is not the piece's business. The result is sorted with [Diagnostic.ORDER].
     */
    public fun inPiece(frame: TextFrame, diagnostics: List<Diagnostic>): List<Diagnostic> =
        diagnostics.mapNotNull { diagnostic -> frame.toPiece(diagnostic.range)?.let { diagnostic.copy(range = it) } }
            .sortedWith(Diagnostic.ORDER)

    /**
     * Every diagnostic of [diagnostics] of the whole text of [frame], in the coordinates of its piece. One that lies in
     * the piece is placed as [inPiece] places it. One outside the piece stands at the nearest end of the piece: at its
     * start if the diagnostic lies before it, else at its end. A frame that cannot map the border of its region puts
     * it at the start of the piece. It suits the syntax errors of a whole text whose text
     * around the piece is free of them. The result is sorted with [Diagnostic.ORDER].
     */
    public fun pinned(frame: TextFrame, diagnostics: List<Diagnostic>): List<Diagnostic> =
        diagnostics.map { diagnostic -> diagnostic.copy(range = pinnedRange(frame, diagnostic.range)) }.sortedWith(Diagnostic.ORDER)

    /** The range of the piece of [frame] for the range [range] of the whole text, by the rule of [pinned]. */
    private fun pinnedRange(frame: TextFrame, range: TextRange): TextRange {
        frame.toPiece(range)?.let { return it }
        val border = if (range.end <= frame.region.start) frame.region.start else frame.region.end
        return frame.toPiece(TextRange.empty(border)) ?: TextRange.EMPTY
    }
}
