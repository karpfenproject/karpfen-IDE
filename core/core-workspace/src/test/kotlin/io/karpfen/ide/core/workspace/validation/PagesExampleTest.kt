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
package io.karpfen.ide.core.workspace.validation

import io.karpfen.ide.core.workspace.analysis.ProjectFragments
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The example project `pages` of the repository: a lamp composed of fragments, one of them used by a container state
 * inside another fragment and one by a container state nested in a state. Its descriptor lists every file, and the
 * whole project validates without a finding.
 */
class PagesExampleTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the pages example validates without findings and its descriptor holds every fragment`() = runTest(timeout = 60.seconds) {
        val root = dir.resolve("pages").toAbsolutePath().normalize()
        val examples = Path.of(checkNotNull(System.getProperty("karpfen.examples")) { "the build names the examples" })
        examples.resolve("pages").toFile().copyRecursively(root.toFile())
        val workspace = TestWorkspaces(this).open(root)

        val report = workspace.validate()

        assertEquals(emptyList(), report.findings)
        val analysis = workspace.analysis.value
        val context = analysis.contexts.single().context
        assertEquals(
            listOf("lamp.kstates", "bulb_test.frag.kstates", "wiring_check.frag.kstates", "warm_up.frag.kstates"),
            context.stateMachines.map { it.file.fileName.toString() },
        )
        assertEquals(listOf("bulb_test", "warm_up", "wiring_check"), ProjectFragments(analysis).names())
        assertEquals(listOf(root.resolve("bulb_test.frag.kstates")), ProjectFragments(analysis).usersOf("wiring_check"))
    }
}
