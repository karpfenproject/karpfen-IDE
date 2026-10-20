package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.document.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReportFormatterTest {

    private val root: Path = Path.of("/work/project").toAbsolutePath()

    private val report = ValidationReport(
        root,
        listOf(
            ReportedProblem("a/robot.kmeta", 3, 14, Severity.ERROR, "missing ',' at '\"number\"'", "syntax-error"),
            ReportedProblem("a/robot.kmeta", 4, 1, Severity.WARNING, "first line\n  second line\r\n", "w"),
            ReportedProblem("b.kenv.json", 1, 2, Severity.INFO, "note", "i"),
            ReportedProblem("c.kstates", 9, 9, Severity.HINT, "hint", "h"),
        ),
    )

    @Test
    fun `the text format prints one line per problem with lower-case severities and joined messages`() {
        val text = ReportFormatter(OutputFormat.TEXT).format(report)

        assertEquals(
            "a/robot.kmeta:3:14: error: missing ',' at '\"number\"' [syntax-error]\n" +
                "a/robot.kmeta:4:1: warning: first line second line [w]\n" +
                "b.kenv.json:1:2: info: note [i]\n" +
                "c.kstates:9:9: hint: hint [h]\n",
            text,
        )
    }

    @Test
    fun `the text format prints nothing without problems`() {
        assertEquals("", ReportFormatter(OutputFormat.TEXT).format(ValidationReport(root, emptyList())))
    }

    @Test
    fun `the JSON format is a parseable document with the root, the counts and every problem`() {
        val output = ReportFormatter(OutputFormat.JSON).format(report)

        assertTrue(output.endsWith("}\n"))
        val document = assertIs<JsonObject>(Json.parseToJsonElement(output))
        assertEquals(JsonPrimitive(root.toString()), document["root"])
        assertEquals(JsonPrimitive(1), document["errorCount"])
        assertEquals(JsonPrimitive(1), document["warningCount"])
        val problems = assertIs<JsonArray>(document["problems"])
        assertEquals(report.problems.size, problems.size)
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
            problems[1],
        )
    }

    @Test
    fun `the JSON format of an empty report has an empty problem list`() {
        val document = Json.parseToJsonElement(ReportFormatter(OutputFormat.JSON).format(ValidationReport(root, emptyList())))

        assertEquals(JsonArray(emptyList()), assertIs<JsonObject>(document)["problems"])
        assertEquals(JsonPrimitive(0), document["errorCount"])
    }
}
