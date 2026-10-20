package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity

/**
 * All diagnostics of a document, merged from every source and expressed in the coordinates of one text [version]
 * (spec §3.4).
 *
 * @property version the text version the ranges refer to.
 * @property diagnostics sorted with [Diagnostic.ORDER], without duplicates.
 */
public data class VersionedDiagnostics(public val version: Long, public val diagnostics: List<Diagnostic>) {

    /** Number of [Severity.ERROR] diagnostics. */
    public val errorCount: Int get() = diagnostics.count { it.severity == Severity.ERROR }

    /** Number of [Severity.WARNING] diagnostics. */
    public val warningCount: Int get() = diagnostics.count { it.severity == Severity.WARNING }

    /** Diagnostics whose range contains [offset] (inclusive bounds, so empty ranges are found too), in order. */
    public fun at(offset: Int): List<Diagnostic> = diagnostics.filter { it.range.containsInclusive(offset) }
}
