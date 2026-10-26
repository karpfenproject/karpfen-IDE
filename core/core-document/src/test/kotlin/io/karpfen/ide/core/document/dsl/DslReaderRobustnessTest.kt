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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.meta.Metamodel
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.ExampleFile
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.text.TextSnapshot
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the robustness of the dsl-tools adapter: on deterministic mutations of every example file, syntax errors
 * included, no exception escapes a read, every diagnostic is a DSL_TOOLS diagnostic inside the read text, and a read
 * without a model reports an error. Models are read against the metamodel of their example context, event metamodels
 * with its types as base types, like the project analysis does.
 */
class DslReaderRobustnessTest {

    private val reader: DslReader = DslReader()

    @Test
    fun `no exception escapes a read of a mutated example and every range stays inside the text`(reporter: TestReporter) {
        var reads = 0
        var withFindings = 0
        Examples.contexts().forEach { context ->
            val domain = requireNotNull(reader.kmeta(TextSnapshot(context.metamodel!!.text, 0)).model)
            context.files.forEach { file ->
                Mutations.random(file.text, seed = SEED + reads, count = MUTATIONS_PER_FILE).forEach { mutation ->
                    val label = "${file.path}, $mutation"
                    val read = try {
                        read(file, TextSnapshot(mutation.text, 0), domain)
                    } catch (e: Exception) {
                        fail("$label threw $e", e)
                    }
                    checkInvariants(read, mutation.text.length, label)
                    reads++
                    if (read.diagnostics.isNotEmpty()) withFindings++
                }
            }
        }
        reporter.publishEntry("dsl-tools reads of mutated examples", "$reads reads, $withFindings with findings")
    }

    /** Reads [source], a mutation of [file], the way the project analysis reads that file next to [domain]. */
    private fun read(file: ExampleFile, source: TextSnapshot, domain: Metamodel): ReadResult<*> =
        when {
            file.language == KarpfenLanguage.KMODEL -> reader.kmodel(source, domain)
            file.language == KarpfenLanguage.KSTATES -> reader.kstates(source)
            file.isEventsMetamodel -> reader.kmeta(source, domain.types)
            else -> reader.kmeta(source)
        }

    /** Asserts the invariants of the class documentation on [read], a read of a text of [length] chars. */
    private fun checkInvariants(read: ReadResult<*>, length: Int, label: String) {
        read.diagnostics.forEach { diagnostic ->
            assertTrue(diagnostic.source == DiagnosticSource.DSL_TOOLS, "$label: $diagnostic")
            assertTrue(diagnostic.range.end <= length, "$label: ${diagnostic.range} outside a text of $length chars")
        }
        assertTrue(read.model != null || read.hasErrors, "$label: no model and no error")
    }

    private companion object {
        /** Mutations of each example file. */
        const val MUTATIONS_PER_FILE = 400

        /** The seed of the first file's mutations; each file's seed is offset by the reads before it. */
        const val SEED = 20_261_022L
    }
}
