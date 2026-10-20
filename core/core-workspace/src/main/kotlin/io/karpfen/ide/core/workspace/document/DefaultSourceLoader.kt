package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.text.LineSeparators
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import java.nio.file.Path

/**
 * [SourceLoader] over a [TextFileStore] and `LineSeparators.normalize`: the one loading rule of the workspace (see
 * [SourceLoader] and [LoadedSource] for the findings and their ranges).
 */
internal class DefaultSourceLoader(private val fileStore: TextFileStore) : SourceLoader {

    override fun load(path: Path): LoadedSource {
        val decoded = fileStore.read(path)
        val normalized = LineSeparators.normalize(decoded.content)
        val diagnostics = buildList {
            if (decoded.malformed) malformedEncoding(normalized.text)?.let(::add)
            if (normalized.mixed) mixedSeparators(decoded.content, normalized.dominant)?.let(::add)
        }
        return LoadedSource(
            path = path,
            text = normalized.text,
            lineSeparator = normalized.dominant,
            hasBom = decoded.hasBom,
            diagnostics = diagnostics.sortedWith(Diagnostic.ORDER),
        )
    }

    /** WARNING on the first replacement character of [text], or null if the decoder produced none. */
    private fun malformedEncoding(text: String): Diagnostic? {
        val offset = text.indexOf(REPLACEMENT_CHARACTER)
        if (offset < 0) return null
        return Diagnostic(
            range = TextRange.ofLength(offset, 1),
            severity = Severity.WARNING,
            message = "The file is not valid UTF-8; invalid bytes were replaced by U+FFFD (�) and are saved that way.",
            code = WorkspaceDiagnosticCodes.MALFORMED_ENCODING,
            source = DiagnosticSource.IDE,
        )
    }

    /**
     * INFO on the first line break whose original separator differs from [dominant]: its range is that line break, the
     * `\n` of the normalized text (every `\r\n` before it shrank by one character).
     */
    private fun mixedSeparators(raw: String, dominant: LineSeparator): Diagnostic? {
        var shrink = 0
        var offset = 0
        while (offset < raw.length) {
            val separator = separatorAt(raw, offset)
            if (separator == null) {
                offset++
                continue
            }
            if (separator != dominant) {
                return Diagnostic(
                    range = TextRange.ofLength(offset - shrink, 1),
                    severity = Severity.INFO,
                    message = "The file mixes line separators (mostly ${dominant.displayName}, this line break is " +
                        "${separator.displayName}); they were normalized and are saved as ${dominant.displayName}.",
                    code = WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS,
                    source = DiagnosticSource.IDE,
                )
            }
            shrink += separator.chars.length - 1
            offset += separator.chars.length
        }
        return null
    }

    private fun separatorAt(raw: String, offset: Int): LineSeparator? = when {
        raw[offset] == '\n' -> LineSeparator.LF
        raw[offset] != '\r' -> null
        offset + 1 < raw.length && raw[offset + 1] == '\n' -> LineSeparator.CRLF
        else -> LineSeparator.CR
    }

    private companion object {
        private const val REPLACEMENT_CHARACTER = '�'
    }
}
