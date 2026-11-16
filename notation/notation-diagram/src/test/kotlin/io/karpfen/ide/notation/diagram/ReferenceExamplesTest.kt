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
package io.karpfen.ide.notation.diagram

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads the KStates examples of the reference chapter on diagrams, `4.3-diagrams.md`, with the reader of dsl-tools, all
 * language features on. Each example shows a text before or after an edit of a statechart, so each must be a text the
 * language accepts. A complete state machine is read as it stands. A `STATES` block is read inside the header of the
 * example lamp with no transitions, which checks its states, among them that the file has one initial state at any
 * depth. Smaller excerpts, a single state or transition, are not read on their own.
 */
class ReferenceExamplesTest {

    @Test
    fun `every complete state machine and every STATES block of the chapter on diagrams reads without errors`() {
        val texts = kstatesBlocks().mapNotNull(::readable)

        assertTrue(texts.size >= 5, "the chapter has its examples: ${texts.size}")
        texts.forEach { text ->
            val read = DslReader().kstates(TextSnapshot(text, 0))
            assertEquals(emptyList(), read.diagnostics.filter { it.severity == Severity.ERROR }.map { it.message }, text)
        }
    }

    /** The text of [block] as a whole state machine, or null for an excerpt smaller than a `STATES` block. */
    private fun readable(block: String): String? = when {
        block.startsWith("STATEMACHINE") -> block
        block.startsWith("    STATES {") -> "STATEMACHINE ATTACHED TO \"Lamp\" {\n$block\n    TRANSITIONS { }\n}\n"
        else -> null
    }

    /** The fenced blocks of the chapter on diagrams that name the language `kstates`, in the order of the file. */
    private fun kstatesBlocks(): List<String> {
        val reference = Path.of(checkNotNull(System.getProperty("karpfen.reference")) { "the build names the reference" })
        val blocks = ArrayList<String>()
        var current: StringBuilder? = null
        Files.readAllLines(reference.resolve("4.3-diagrams.md")).forEach { line ->
            val open = current
            when {
                open == null && line.trim() == "```kstates" -> current = StringBuilder()
                open != null && line.trim() == "```" -> {
                    blocks += open.toString().trimEnd()
                    current = null
                }
                open != null -> open.appendLine(line)
            }
        }
        return blocks
    }
}
