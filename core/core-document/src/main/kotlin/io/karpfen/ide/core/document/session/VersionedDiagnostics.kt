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
 * Carrying the snapshot (not only its version) lets consumers compute positions of exactly the text the
 * ranges refer to while the diagnostics lag behind the live text (e.g. `FileProblems.positionOf` in core-workspace),
 * without mapping ranges themselves.
 *
 * @property snapshot the text the ranges refer to.
 * @property diagnostics sorted with [Diagnostic.ORDER], without duplicates.
 */
public data class VersionedDiagnostics(public val snapshot: TextSnapshot, public val diagnostics: List<Diagnostic>) {

    /** The version of [snapshot]. */
    public val version: Long get() = snapshot.version

    /** Number of [Severity.ERROR] diagnostics. */
    public val errorCount: Int get() = diagnostics.count { it.severity == Severity.ERROR }

    /** Number of [Severity.WARNING] diagnostics. */
    public val warningCount: Int get() = diagnostics.count { it.severity == Severity.WARNING }

    /** Diagnostics whose range contains [offset] (inclusive bounds, so empty ranges are found too), in order. */
    public fun at(offset: Int): List<Diagnostic> = diagnostics.filter { it.range.containsInclusive(offset) }
}
