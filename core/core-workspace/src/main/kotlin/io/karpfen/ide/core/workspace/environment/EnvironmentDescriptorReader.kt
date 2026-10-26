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
package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.core.text.TextRange
import java.nio.file.Path

/**
 * Reads environment descriptors. Pure: does not touch the file system (missing referenced files are detected by the
 * `ContextResolver`, which uses [DescriptorReadResult.references] for ranges).
 *
 * Never throws. Findings, all with ranges in the text passed to [read] (the `\n`-normalized text loaded by the
 * workspace's `SourceLoader`, or the editor text of an open descriptor, so `line:col` in the Problems view and the CLI
 * is computed like for every other file), come in two kinds:
 * - the syntax errors of the text ([DescriptorReadResult.syntaxDiagnostics]) are the LEXER and PARSER errors
 *   (`lexer-error`, `syntax-error`) of the environment language, exactly what the editor computes for the same text;
 *   they belong to the text, as a karpfen file's own diagnostics do, and the descriptor is then not read any further;
 * - the descriptor's findings ([DescriptorReadResult.diagnostics]) are its checks against `EnvironmentSchema`: unknown
 *   keys are WARNINGs `descriptor-unknown-key` at the key; missing required keys, repeated keys and values of the wrong
 *   kind are ERRORs `descriptor-invalid`. With the findings about the files it names, they are the descriptor's project
 *   diagnostics.
 *
 * A descriptor with an error of either kind yields no [DescriptorReadResult.descriptor] and no references.
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
 * @property diagnostics the descriptor's findings against the schema, sorted with [Diagnostic.ORDER]; empty while the
 *   text has syntax errors.
 * @property references every file named by the descriptor, with the range of its JSON string value (inside the
 *   quotes), in document order.
 * @property syntaxDiagnostics the syntax errors of the text, sorted with [Diagnostic.ORDER]: what the editor of an open
 *   descriptor computes itself, so they are never among its project diagnostics.
 */
public data class DescriptorReadResult(
    public val descriptor: EnvironmentDescriptor?,
    public val diagnostics: List<Diagnostic>,
    public val references: List<DescriptorFileReference>,
    public val syntaxDiagnostics: List<Diagnostic> = emptyList(),
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
