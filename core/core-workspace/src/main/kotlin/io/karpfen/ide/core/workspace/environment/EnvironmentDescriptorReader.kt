package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextRange
import java.nio.file.Path

/**
 * Parses environment descriptors with kotlinx.serialization. Pure: does not touch the file system (missing referenced
 * files are detected by the `ContextResolver`, which uses [DescriptorReadResult.references] for ranges).
 *
 * Never throws: invalid JSON, missing required keys or a repeated key → ERROR `descriptor-invalid` (no descriptor);
 * unknown keys → WARNING `descriptor-unknown-key` at the key. All diagnostics have source IDE and ranges in the text
 * passed to [read], which is the `\n`-normalized text loaded by the workspace's `SourceLoader` (so `line:col` in the
 * Problems view and the CLI is computed like for every other file).
 */
public fun interface EnvironmentDescriptorReader {
    /** Reads the descriptor at [path] with the normalized content [text]. */
    public fun read(path: Path, text: String): DescriptorReadResult
}

/** The kotlinx.serialization-based [EnvironmentDescriptorReader]. */
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

/** A file named in a descriptor: [value] as written (relative path) and its [range] in the descriptor text. */
public data class DescriptorFileReference(
    public val role: DescriptorFileRole,
    public val value: String,
    public val range: TextRange,
)

/** Which descriptor key a [DescriptorFileReference] comes from. */
public enum class DescriptorFileRole {
    /** `metamodel`. */
    METAMODEL,

    /** `events`. */
    EVENTS,

    /** `model`. */
    MODEL,

    /** `stateMachines[].file`. */
    STATE_MACHINE,

    /** `bridges[]`. */
    BRIDGE,
}
