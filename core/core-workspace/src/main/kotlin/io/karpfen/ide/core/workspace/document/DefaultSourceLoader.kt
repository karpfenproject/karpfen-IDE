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
 * [SourceLoader] over a [TextFileStore] and `LineSeparators.normalize`. It is the single loading rule of the
 * workspace. [SourceLoader] and [LoadedSource] describe the findings and their ranges.
 */
internal class DefaultSourceLoader(private val fileStore: TextFileStore) : SourceLoader {

    override fun load(path: Path): LoadedSource = loaded(path, fileStore.read(path))

    override fun loadWritten(path: Path, content: String, withBom: Boolean): LoadedSource =
        loaded(path, fileStore.readBack(content, withBom))

    /** The rule itself. It turns [decoded], the text of [path], into editor form with its findings. */
    private fun loaded(path: Path, decoded: DecodedText): LoadedSource {
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

    /** A WARNING on the first replacement character of [text], or null if the decoder produced none. */
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
     * An INFO on the first line break whose original separator differs from [dominant]. Its range is that line break,
     * the `\n` of the normalized text. Every `\r\n` before it shrank by one character, and the offset accounts for
     * that.
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

    /** The line separator starting at [offset] of [raw], or null if no line break starts there. */
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
