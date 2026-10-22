package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.LineSeparator
import java.nio.file.Path

/**
 * Loads a file into editor form: reads it as UTF-8 through a [TextFileStore] (malformed bytes replaced, never
 * rejected), normalizes line separators to `\n` (`LineSeparators.normalize`, remembering the dominant one for saving)
 * and reports the loading findings. The one loading
 * rule of the workspace: the [DocumentManager] (open, reload), the project analysis (files that are not open) and the
 * descriptor reading all load through it, so every consumer sees the same text and the same findings. Blocking; called
 * on an IO dispatcher.
 */
public fun interface SourceLoader {
    /**
     * Loads [path].
     * @throws java.io.IOException if it cannot be read.
     */
    public fun load(path: Path): LoadedSource

    /**
     * What [load] returns for [path] right after `TextFileStore.write(path, content, withBom)`, computed without I/O
     * (`TextFileStore.readBack`): the disk state a save leaves, so the save's own watch event is recognized however the
     * loading rule evolves. The default applies the default rule; a loader with a rule of its own overrides it to agree
     * with [load] (findings aside: an open document compares disk states by their content, not their findings).
     */
    public fun loadWritten(path: Path, content: String, withBom: Boolean): LoadedSource =
        DefaultSourceLoader(TextFileStore()).loadWritten(path, content, withBom)
}

/** The default [SourceLoader], reading through [fileStore]. */
public fun SourceLoader(fileStore: TextFileStore = TextFileStore()): SourceLoader = DefaultSourceLoader(fileStore)

/**
 * A loaded file.
 *
 * @property text the content with `\n` separators only and without byte order mark (what an `EditableText` holds and
 *   what every range of the workspace refers to).
 * @property lineSeparator the dominant separator on disk, restored on save.
 * @property hasBom true if the file started with a UTF-8 byte order mark, restored on save.
 * @property diagnostics the findings of loading, source IDE, ranges in [text], sorted with [Diagnostic.ORDER]: a WARNING
 *   `malformed-encoding` on the first replacement character if the bytes were not valid UTF-8, and an INFO
 *   `mixed-line-separators` on the first line break (the `\n` in [text]) whose original separator differs from
 *   [lineSeparator]. Open documents publish them as external source `ExternalDiagnosticSources.FILE`.
 */
public data class LoadedSource(
    public val path: Path,
    public val text: String,
    public val lineSeparator: LineSeparator,
    public val hasBom: Boolean,
    public val diagnostics: List<Diagnostic>,
)
