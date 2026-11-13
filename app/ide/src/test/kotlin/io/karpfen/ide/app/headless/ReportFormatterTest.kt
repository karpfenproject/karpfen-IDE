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
package io.karpfen.ide.app.headless

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.core.workspace.validation.ValidationFinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [ReportFormatter]: the text of [ValidationLines] and the JSON document with its members in their order. */
class ReportFormatterTest {

    private val root: Path = Path.of("/work/project").toAbsolutePath().normalize()

    private val validation = ProjectValidation(
        root,
        listOf(
            ValidationFinding(root.resolve("a/robot.kmeta"), 3, 14, Severity.ERROR, "syntax-error", "missing ',' at '\"number\"'"),
            ValidationFinding(root.resolve("a/robot.kmeta"), 4, 1, Severity.WARNING, "w", "first line\n  second line\r\n"),
            ValidationFinding(root.resolve("b.kenv.json"), 1, 2, Severity.INFO, "i", "note"),
            ValidationFinding(root.resolve("c.kstates"), 9, 9, Severity.HINT, "h", "hint"),
        ).sortedWith(ValidationFinding.ORDER),
    )

    @Test
    fun `the text format is the lines of the validation as the window's report has them`() {
        assertEquals(ValidationLines.of(validation), ReportFormatter(OutputFormat.TEXT).lines(validation))
    }

    @Test
    fun `the JSON format is a parseable document with the root, the counts and every problem`() {
        val output = ReportFormatter(OutputFormat.JSON).lines(validation).joinToString("\n")

        val document = assertIs<JsonObject>(Json.parseToJsonElement(output))
        assertEquals(listOf("root", "errorCount", "warningCount", "problems"), document.keys.toList())
        assertEquals(JsonPrimitive(root.toString()), document["root"])
        assertEquals(JsonPrimitive(1), document["errorCount"])
        assertEquals(JsonPrimitive(1), document["warningCount"])
        val problems = assertIs<JsonArray>(document["problems"])
        assertEquals(validation.findings.size, problems.size)
        val warning = assertIs<JsonObject>(problems[1])
        assertEquals(listOf("path", "line", "column", "severity", "message", "code"), warning.keys.toList())
        assertEquals(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive("a/robot.kmeta"),
                    "line" to JsonPrimitive(4),
                    "column" to JsonPrimitive(1),
                    "severity" to JsonPrimitive("warning"),
                    "message" to JsonPrimitive("first line\n  second line\r\n"),
                    "code" to JsonPrimitive("w"),
                ),
            ),
            warning,
        )
    }

    @Test
    fun `the JSON format of an empty validation has an empty problem list`() {
        val output = ReportFormatter(OutputFormat.JSON).lines(ProjectValidation(root, emptyList())).joinToString("\n")
        val document = assertIs<JsonObject>(Json.parseToJsonElement(output))

        assertEquals(JsonArray(emptyList()), document["problems"])
        assertEquals(JsonPrimitive(0), document["errorCount"])
    }
}
