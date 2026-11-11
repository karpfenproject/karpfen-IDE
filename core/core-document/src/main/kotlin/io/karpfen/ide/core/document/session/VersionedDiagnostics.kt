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
package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextSnapshot

/**
 * All diagnostics of a document, merged from every source and expressed in the coordinates of one text [snapshot].
 * The diagnostics may lag behind the live text. They carry the snapshot and not only its version, so consumers can
 * compute positions in exactly the text the ranges refer to, without mapping ranges themselves.
 * `FileProblems.positionOf` in core-workspace does this.
 *
 * @property snapshot the text the ranges refer to.
 * @property diagnostics the diagnostics, sorted with [Diagnostic.ORDER] and without duplicates.
 */
public data class VersionedDiagnostics(public val snapshot: TextSnapshot, public val diagnostics: List<Diagnostic>) {

    /** The version of [snapshot]. */
    public val version: Long get() = snapshot.version

    /** The number of [Severity.ERROR] diagnostics. */
    public val errorCount: Int get() = diagnostics.count { it.severity == Severity.ERROR }

    /** The number of [Severity.WARNING] diagnostics. */
    public val warningCount: Int get() = diagnostics.count { it.severity == Severity.WARNING }

    /**
     * The diagnostics whose range contains [offset], in order. The bounds are inclusive, so empty ranges are found
     * too.
     */
    public fun at(offset: Int): List<Diagnostic> = diagnostics.filter { it.range.containsInclusive(offset) }
}
