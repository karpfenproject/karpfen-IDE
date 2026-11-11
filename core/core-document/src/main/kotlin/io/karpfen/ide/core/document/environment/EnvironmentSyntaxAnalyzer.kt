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
package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.json.JsonParser
import io.karpfen.ide.core.document.json.containers
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The syntax analysis of environment descriptors. [JsonParser] reads the text into a tree, also a broken one. The tree
 * yields the syntax diagnostics of the lexer and the parser. It also yields the folding regions, which are every
 * object and list that is closed and spans several lines. One walk of the tree along the [schema], [shapedValues],
 * yields the outline through [DescriptorOutline] and the semantic spans through [DescriptorSpans]. A descriptor
 * declares and references no symbols, so its syntax index is empty. The language services resolve what its strings
 * name. The class holds no state and is thread-safe.
 *
 * The descriptor checks beyond syntax belong to the workspace. They cover unknown and missing keys, values of the
 * wrong kind and missing files. The workspace reads every descriptor against the same schema and reports the findings
 * to the open editor as project diagnostics.
 */
internal class EnvironmentSyntaxAnalyzer(private val schema: ValueShape.Record = EnvironmentSchema.ROOT) : SyntaxAnalyzer {

    override val language: KarpfenLanguage = KarpfenLanguage.ENVIRONMENT

    override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis {
        val document = JsonParser.parse(snapshot.text)
        val values = document.root?.let { shapedValues(it, schema) }.orEmpty()
        return SyntaxAnalysis(
            syntaxDiagnostics = document.diagnostics,
            outline = DescriptorOutline.of(values),
            index = SyntaxIndex.EMPTY,
            folding = document.root?.let { folding(snapshot, it) }.orEmpty(),
            semanticSpans = DescriptorSpans.of(values),
        )
    }

    /** A BLOCK region for every closed object and list that spans several lines, from [root] down. */
    private fun folding(snapshot: TextSnapshot, root: JsonNode): List<FoldingRange> =
        root.containers()
            .filter { it.isClosed && isMultiLine(snapshot, it.range) }
            .map { FoldingRange(it.range, FoldingKind.BLOCK) }
            .sortedBy { it.range.start }

    private fun isMultiLine(snapshot: TextSnapshot, range: TextRange): Boolean =
        snapshot.lineIndex.lineOf(range.start) < snapshot.lineIndex.lineOf(range.end)
}
