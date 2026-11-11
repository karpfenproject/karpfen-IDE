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
import io.karpfen.ide.core.text.LineSeparator
import java.nio.file.Path

/**
 * Loads a file into editor form. It reads the file as UTF-8 through a [TextFileStore]. Malformed bytes are replaced,
 * never rejected. It normalizes line separators to `\n` with `LineSeparators.normalize` and remembers the dominant one
 * for saving. It reports the loading findings.
 *
 * This is the single loading rule of the workspace. The [DocumentManager] loads through it when it opens or reloads a
 * document. The project analysis loads the files that are not open through it, and so does the descriptor reading. So
 * every consumer sees the same text and the same findings. Loading blocks, so call it on an IO dispatcher.
 */
public fun interface SourceLoader {
    /**
     * Loads [path].
     *
     * @throws java.io.IOException if the file cannot be read.
     */
    public fun load(path: Path): LoadedSource

    /**
     * What [load] returns for [path] right after `TextFileStore.write(path, content, withBom)`. It is computed without
     * I/O through `TextFileStore.readBack`. It is the disk state that a save leaves, so the watch event of the save is
     * recognized however the loading rule changes. The default applies the default rule. A loader with a rule of its
     * own overrides it to agree with [load]. The findings may differ, because an open document compares disk states by
     * their content and not by their findings.
     */
    public fun loadWritten(path: Path, content: String, withBom: Boolean): LoadedSource =
        DefaultSourceLoader(TextFileStore()).loadWritten(path, content, withBom)
}

/** The default [SourceLoader], which reads through [fileStore]. */
public fun SourceLoader(fileStore: TextFileStore = TextFileStore()): SourceLoader = DefaultSourceLoader(fileStore)

/**
 * A loaded file.
 *
 * @property text the content with `\n` separators only and without a byte order mark. An `EditableText` holds this
 *   text, and every range of the workspace refers to it.
 * @property lineSeparator the dominant line separator on disk. It is restored on save.
 * @property hasBom true if the file started with a UTF-8 byte order mark. It is restored on save.
 * @property diagnostics the findings of loading. They have the source IDE, their ranges lie in [text], and they are
 *   sorted with [Diagnostic.ORDER]. If the bytes were not valid UTF-8, there is a WARNING `malformed-encoding` on the
 *   first replacement character. There is an INFO `mixed-line-separators` on the first line break whose original
 *   separator differs from [lineSeparator]. Its range is the `\n` in [text]. Open documents publish these findings as
 *   the external source `ExternalDiagnosticSources.FILE`.
 */
public data class LoadedSource(
    public val path: Path,
    public val text: String,
    public val lineSeparator: LineSeparator,
    public val hasBom: Boolean,
    public val diagnostics: List<Diagnostic>,
)
