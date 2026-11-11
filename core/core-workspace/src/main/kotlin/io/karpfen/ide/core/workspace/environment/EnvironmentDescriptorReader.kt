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
 * Reads environment descriptors. It does not touch the file system. Missing referenced files are detected by the
 * `ContextResolver`, which takes the ranges from [DescriptorReadResult.references].
 *
 * It never throws. All findings have ranges in the text passed to [read]. That text is the `\n`-normalized text that
 * the workspace's `SourceLoader` loaded, or the editor text of an open descriptor. So `line:col` in the Problems view
 * and in the command line tool is computed as for every other file. There are two kinds of findings.
 * - The syntax errors of the text, in [DescriptorReadResult.syntaxDiagnostics], are the LEXER and PARSER errors of the
 *   environment language, with the codes `lexer-error` and `syntax-error`. They are exactly what the editor computes
 *   for the same text. They belong to the text, as the own diagnostics of a karpfen file do. If there are any, the
 *   descriptor is not read any further.
 * - The findings of the descriptor, in [DescriptorReadResult.diagnostics], are its checks against `EnvironmentSchema`.
 *   An unknown key is a WARNING `descriptor-unknown-key` at the key. A missing required key, a repeated key and a value
 *   of the wrong kind are ERRORs `descriptor-invalid`. Together with the findings about the files it names, they are
 *   the project diagnostics of the descriptor.
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
 * @property diagnostics the findings of the descriptor against the schema, sorted with [Diagnostic.ORDER]. It is empty
 *   while the text has syntax errors.
 * @property references every file the descriptor names, with the range of its JSON string value inside the quotes, in
 *   document order.
 * @property syntaxDiagnostics the syntax errors of the text, sorted with [Diagnostic.ORDER]. The editor of an open
 *   descriptor computes them itself, so they are never among its project diagnostics.
 */
public data class DescriptorReadResult(
    public val descriptor: EnvironmentDescriptor?,
    public val diagnostics: List<Diagnostic>,
    public val references: List<DescriptorFileReference>,
    public val syntaxDiagnostics: List<Diagnostic> = emptyList(),
)

/**
 * A file named in a descriptor. [value] is the path as written, relative and with escapes decoded. [role] is what the
 * file is for, and [range] is where the value lies in the descriptor text.
 */
public data class DescriptorFileReference(
    public val role: DescriptorFileRole,
    public val value: String,
    public val range: TextRange,
)
