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

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers [HeadlessCommand.parse]: the command lines that open the window, every form of `validate` and `run`, help,
 * and every usage error. The folder check is a set of folders the test names, so no file system is touched.
 */
class HeadlessCommandTest {

    private val project: Path = Path.of("project").toAbsolutePath().normalize()

    @Test
    fun `a command line that does not start with validate or run opens the window`() {
        assertNull(parse())
        assertNull(parse("project"))
        assertNull(parse("--verbose", "run", "project"))
        assertNull(parse("Validate", "project"))
    }

    @Test
    fun `validate takes one folder, which is made absolute and normalized`() {
        assertEquals(HeadlessCommand.Validate(project), parse("validate", "project"))
        assertEquals(HeadlessCommand.Validate(project), parse("validate", "./other/../project"))
        assertEquals(HeadlessCommand.Validate(project), parse("validate", "--", "project"))
    }

    @Test
    fun `validate prints text unless --format chooses json, before or after the folder, with or without an equals sign`() {
        assertEquals(OutputFormat.TEXT, (parse("validate", "project") as HeadlessCommand.Validate).format)
        assertEquals(HeadlessCommand.Validate(project, OutputFormat.JSON), parse("validate", "project", "--format", "json"))
        assertEquals(HeadlessCommand.Validate(project, OutputFormat.JSON), parse("validate", "--format=JSON", "project"))
        assertEquals(HeadlessCommand.Validate(project, OutputFormat.TEXT), parse("validate", "project", "--format=text"))
    }

    @Test
    fun `a missing, unknown or second format and a format for run are usage errors`() {
        assertEquals(HeadlessCommand.Invalid("--format needs text or json"), parse("validate", "project", "--format"))
        assertEquals(HeadlessCommand.Invalid("--format takes text or json, not 'xml'"), parse("validate", "project", "--format", "xml"))
        assertEquals(
            HeadlessCommand.Invalid("--format may be given once"),
            parse("validate", "project", "--format", "json", "--format", "text"),
        )
        assertEquals(HeadlessCommand.Invalid("run takes no option '--format'"), parse("run", "project", "--format", "json"))
    }

    @Test
    fun `run without options takes the only descriptor and starts no bridge`() {
        assertEquals(HeadlessCommand.Run(project), parse("run", "project"))
    }

    @Test
    fun `run reads every option before and after the folder, with values as the next argument or after an equals sign`() {
        val command = parse(
            "run", "--capture", "project", "--descriptor", "lamp/lamp.kenv.json",
            "--bridge=http.kbridge.json", "--bridge", "lab/ros.kbridge.json", "--verbose",
        )

        assertEquals(
            HeadlessCommand.Run(
                folder = project,
                descriptor = project.resolve("lamp/lamp.kenv.json"),
                bridges = listOf(project.resolve("http.kbridge.json"), project.resolve("lab/ros.kbridge.json")),
                capture = true,
                verbose = true,
            ),
            command,
        )
    }

    @Test
    fun `an absolute descriptor stays as it is and a relative one is normalized against the folder`() {
        val elsewhere = Path.of("elsewhere/lamp.kenv.json").toAbsolutePath().normalize()

        assertEquals(elsewhere, (parse("run", "project", "--descriptor", elsewhere.toString()) as HeadlessCommand.Run).descriptor)
        assertEquals(
            project.resolve("lamp.kenv.json"),
            (parse("run", "project", "--descriptor=sub/../lamp.kenv.json") as HeadlessCommand.Run).descriptor,
        )
    }

    @Test
    fun `after the end of the options a folder may start with a dash`() {
        val dashed = Path.of("-project").toAbsolutePath().normalize()

        assertEquals(HeadlessCommand.Run(dashed), parse("run", "--", "-project", folders = setOf(dashed)))
    }

    @Test
    fun `help after a command asks for the usage, whatever else is given`() {
        assertEquals(HeadlessCommand.Help, parse("validate", "--help"))
        assertEquals(HeadlessCommand.Help, parse("run", "-h"))
        assertEquals(HeadlessCommand.Help, parse("run", "project", "--capture", "--help", "--bogus"))
    }

    @Test
    fun `a missing, empty, second or absent folder is a usage error`() {
        assertEquals(HeadlessCommand.Invalid("validate needs a project folder"), parse("validate"))
        assertEquals(HeadlessCommand.Invalid("run needs a project folder"), parse("run", "--capture"))
        assertEquals(HeadlessCommand.Invalid("run needs a project folder, not an empty argument"), parse("run", ""))
        assertEquals(
            HeadlessCommand.Invalid("unexpected argument 'other' (validate takes one project folder)"),
            parse("validate", "project", "other"),
        )
        val missing = Path.of("missing").toAbsolutePath().normalize()
        assertEquals(HeadlessCommand.Invalid("$missing is not a folder"), parse("run", "missing"))
    }

    @Test
    fun `an unknown option and an option that validate does not take are usage errors`() {
        assertEquals(HeadlessCommand.Invalid("unknown option '--bogus'"), parse("run", "project", "--bogus"))
        assertEquals(HeadlessCommand.Invalid("unknown option '--capture=yes'"), parse("run", "project", "--capture=yes"))
        assertEquals(HeadlessCommand.Invalid("validate takes no option '--capture'"), parse("validate", "project", "--capture"))
        assertEquals(HeadlessCommand.Invalid("unknown option '--bogus'"), parse("validate", "project", "--bogus"))
        assertEquals(
            HeadlessCommand.Invalid("validate takes no option '--descriptor'"),
            parse("validate", "--descriptor", "lamp.kenv.json", "project"),
        )
    }

    @Test
    fun `an option without its file, a second descriptor and a bridge named twice are usage errors`() {
        assertEquals(HeadlessCommand.Invalid("--descriptor needs a file"), parse("run", "project", "--descriptor"))
        assertEquals(HeadlessCommand.Invalid("--bridge needs a file"), parse("run", "project", "--bridge="))
        assertEquals(
            HeadlessCommand.Invalid("--descriptor may be given once"),
            parse("run", "project", "--descriptor", "a.kenv.json", "--descriptor", "b.kenv.json"),
        )
        assertEquals(
            HeadlessCommand.Invalid("the bridge http.kbridge.json is named twice"),
            parse("run", "project", "--bridge", "http.kbridge.json", "--bridge", "./http.kbridge.json"),
        )
    }

    @Test
    fun `a path the file system cannot represent is a usage error`() {
        val command = parse("run", "project", "--descriptor", "lamp\u0000.kenv.json")

        assertEquals(HeadlessCommand.Invalid("invalid path 'lamp\u0000.kenv.json'"), command)
    }

    @Test
    fun `the word of a command is a folder after the command`() {
        val folder = Path.of("run").toAbsolutePath().normalize()

        assertIs<HeadlessCommand.Validate>(parse("validate", "run", folders = setOf(folder)))
    }

    /** Parses [args] with [folders] as the only existing folders. */
    private fun parse(vararg args: String, folders: Set<Path> = setOf(project)): HeadlessCommand? =
        HeadlessCommand.parse(args.toList(), isDirectory = { it in folders })
}
