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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Covers [ProjectFragments]: the fragments of a project by name, the fragment of the own context before one of
 * another, and the files that use a fragment.
 */
class ProjectFragmentsTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    @Test
    fun `fragments are found by name, those of the own context first, and their users across the project`() =
        runTest(timeout = 60.seconds) {
            val host = write("a/lamp.kstates", MACHINE)
            val near = write("a/check.frag.kstates", fragment("check"))
            val far = write("b/check.frag.kstates", fragment("check"))
            write("b/wiring.frag.kstates", fragment("wiring"))
            val user = write("b/user.frag.kstates", fragment("user").replace("INITIAL STATE \"start\" { }", "INITIAL CONTAINER STATE \"start\" USE \"check\" { }"))

            val fragments = ProjectFragments(TestWorkspaces(this).open(root).awaitAnalysis())

            assertEquals(listOf("check", "user", "wiring"), fragments.names())
            assertEquals(near, fragments.named("check", near = host)?.path)
            assertEquals(far, fragments.named("check", near = user)?.path)
            assertNull(fragments.named("missing"))
            assertEquals(listOf(host, user).sorted(), fragments.usersOf("check").sorted())
            assertEquals(emptyList(), fragments.usersOf("wiring"))
        }

    /** Writes [text] to [name] below the root and returns its path. */
    private fun write(name: String, text: String): Path {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        return Files.writeString(path, text).toAbsolutePath().normalize()
    }

    /** A fragment named [name] with an initial and a final state. */
    private fun fragment(name: String): String =
        "STATEMACHINE \"$name\" FRAGMENT {\n STATES {\n  INITIAL STATE \"start\" { }\n  FINAL STATE \"done\" { }\n }\n" +
            " TRANSITIONS {\n  TRANSITION \"start\" -> \"done\" { }\n }\n}\n"

    private companion object {
        val MACHINE = "STATEMACHINE ATTACHED TO \"Lamp\" {\n STATES {\n  INITIAL CONTAINER STATE \"test\" USE \"check\" { }\n }\n" +
            " TRANSITIONS { }\n}\n"
    }
}
