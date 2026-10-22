package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.text.TextRange
import java.nio.file.Path

/**
 * Reads environment descriptors. Pure: does not touch the file system (missing referenced files are detected by the
 * `ContextResolver`, which uses [DescriptorReadResult.references] for ranges).
 *
 * Never throws. Findings, all with ranges in the text passed to [read] (the `\n`-normalized text loaded by the
 * workspace's `SourceLoader`, or the editor text of an open descriptor, so `line:col` in the Problems view and the CLI
 * is computed like for every other file):
 * - JSON syntax errors are the LEXER and PARSER errors (`lexer-error`, `syntax-error`) of the environment language,
 *   exactly what the editor shows for the same text; the descriptor is then not read any further;
 * - checks against `EnvironmentSchema`: unknown keys are WARNINGs `descriptor-unknown-key` at the key; missing
 *   required keys, repeated keys and values of the wrong kind are ERRORs `descriptor-invalid`;
 * - a descriptor with an error yields no [DescriptorReadResult.descriptor] and no references.
 */
public fun interface EnvironmentDescriptorReader {
    /** Reads the descriptor at [path] with the normalized content [text]. */
    public fun read(path: Path, text: String): DescriptorReadResult
}

/** The [EnvironmentDescriptorReader] that checks against `EnvironmentSchema` and decodes with kotlinx.serialization. */
public fun EnvironmentDescriptorReader(): EnvironmentDescriptorReader = JsonEnvironmentDescriptorReader()

/**
 * Result of reading a descriptor.
 *
 * @property descriptor the descriptor, or null if it could not be decoded.
 * @property diagnostics findings in the descriptor text, sorted with [Diagnostic.ORDER].
 * @property references every file named by the descriptor, with the range of its JSON string value (inside the
 *   quotes), in document order.
 */
public data class DescriptorReadResult(
    public val descriptor: EnvironmentDescriptor?,
    public val diagnostics: List<Diagnostic>,
    public val references: List<DescriptorFileReference>,
)

/**
 * A file named in a descriptor: [value] as written (relative path, escapes decoded), its [role] and its [range] in the
 * descriptor text.
 */
public data class DescriptorFileReference(
    public val role: DescriptorFileRole,
    public val value: String,
    public val range: TextRange,
)
