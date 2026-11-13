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
package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.core.workspace.validation.ValidationFinding
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The mapping of a validation of the workspace onto the report: relative `/` paths, positions and their order. */
class ReportMappingTest {

    private val root: Path = Path.of("/work/project").toAbsolutePath().normalize()

    private fun finding(relative: String, line: Int, column: Int, severity: Severity = Severity.ERROR, code: String = "c$line") =
        ValidationFinding(root.resolve(relative), line, column, severity, code, "message $line:$column")

    private fun validation(vararg findings: ValidationFinding) = ProjectValidation(root, findings.sortedWith(ValidationFinding.ORDER))

    @Test
    fun `every finding becomes a problem with its position and a slash path relative to the root`() {
        val report = validation(
            finding("a/b/robot.kstates", 1, 1),
            finding("a/b/robot.kstates", 2, 3, Severity.WARNING),
            finding("a/b/robot.kstates", 3, 7, code = "project.unknown-path"),
        ).toValidationReport(root)

        assertEquals(root, report.root)
        assertEquals(
            listOf(
                ReportedProblem("a/b/robot.kstates", 1, 1, Severity.ERROR, "message 1:1", "c1"),
                ReportedProblem("a/b/robot.kstates", 2, 3, Severity.WARNING, "message 2:3", "c2"),
                ReportedProblem("a/b/robot.kstates", 3, 7, Severity.ERROR, "message 3:7", "project.unknown-path"),
            ),
            report.problems,
        )
    }

    @Test
    fun `problems are sorted by path, line and column and keep the order of the validation for ties`() {
        val report = validation(
            finding("z.kmeta", 2, 1),
            finding("a.kmodel", 2, 1, Severity.INFO, "info"),
            finding("a.kmodel", 2, 1, Severity.ERROR, "error"),
            finding("a.kmodel", 1, 1),
        ).toValidationReport(root)

        assertEquals(
            listOf("a.kmodel:1:1:c1", "a.kmodel:2:1:error", "a.kmodel:2:1:info", "z.kmeta:2:1:c2"),
            report.problems.map { "${it.path}:${it.line}:${it.column}:${it.code}" },
        )
    }

    @Test
    fun `no findings give an empty report without errors`() {
        val report = validation().toValidationReport(root)

        assertEquals(ValidationReport(root, emptyList()), report)
        assertEquals(false, report.hasErrors)
    }

    @Test
    fun `report paths are relative with slashes below the root and absolute outside it`() {
        assertEquals("ctx/robot.kenv.json", reportPath(root, root.resolve("ctx").resolve("robot.kenv.json")))
        assertEquals("x.kmeta", reportPath(root, root.resolve("ctx/../x.kmeta")))
        assertEquals(".", reportPath(root, root), "the root itself")
        val outside = root.resolveSibling("other").resolve("y.kmeta")
        assertEquals(outside.toString().replace('\\', '/'), reportPath(root, outside))
    }
}
