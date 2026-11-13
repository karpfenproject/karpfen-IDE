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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.switchOnLanguageFeatures
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The switches of the optional language features of KStates in the Run menu. Each feature has one command whose title
 * says what a click does. A click stores the choice in the project file, hands it to the workspace and analyzes the
 * project again. The commands are disabled while no folder is open. The examples use the optional constructs, so they
 * have errors until the features are switched on.
 */
class LanguageFeatureCommandsTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()

    private fun scenario(block: suspend TestWorkbench.(CommandRegistry) -> Unit) = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(dir.resolve("settings")) {
            val registry = CommandRegistry(services.platform)
            state.registerCommands(registry)
            settle()
            block(registry)
        }
    }

    /** The registered switch of [feature]. */
    private fun CommandRegistry.switchOf(feature: KstatesFeature) =
        assertNotNull(find(WorkbenchCommandIds.languageFeature(feature)), "no switch of $feature")

    @Test
    fun `without a folder every feature has a disabled switch that would switch it on`() = scenario { registry ->
        assertEquals(
            listOf(
                "Switch On Histories",
                "Switch On Tick limits",
                "Switch On Breakpoints",
                "Switch On Macros",
                "Switch On Splits and joins",
            ),
            KstatesFeature.entries.map { registry.switchOf(it).title },
        )
        KstatesFeature.entries.forEach { assertFalse(registry.switchOf(it).isEnabled.value, "$it") }
    }

    @Test
    fun `switching every feature on stores the choice, renames the switches and clears the errors of the examples`() =
        scenario { registry ->
            open(root)
            assertEquals(emptySet(), workspace.languageFeatures.value)
            assertTrue(state.problems.state.value.errorCount > 0, "the examples use constructs of features that are off")
            KstatesFeature.entries.forEach { assertTrue(registry.switchOf(it).isEnabled.value, "$it") }

            KstatesFeature.entries.forEach { registry.execute(WorkbenchCommandIds.languageFeature(it)) }
            settle()

            assertEquals(KstatesFeature.ALL, state.settings.value.languageFeatures)
            assertEquals(KstatesFeature.ALL, workspace.languageFeatures.value)
            assertEquals(KstatesFeature.ALL, workspace.analysis.value.languageFeatures)
            assertEquals(0, state.problems.state.value.errorCount)
            assertEquals("Switch Off Macros", registry.switchOf(KstatesFeature.MACROS).title)
            val lines = Files.readAllLines(root.resolve(ProjectSettings.FILE_NAME))
            KstatesFeature.entries.forEach { assertTrue("feature.${it.id} = on" in lines, "$it in $lines") }
        }

    @Test
    fun `switching a feature off again reports its constructs again`() = scenario { registry ->
        switchOnLanguageFeatures(root)
        open(root)
        assertEquals(KstatesFeature.ALL, workspace.languageFeatures.value, "the project file is handed to the workspace")
        assertEquals(0, state.problems.state.value.errorCount)

        registry.execute(WorkbenchCommandIds.languageFeature(KstatesFeature.MACROS))
        settle()

        assertEquals(KstatesFeature.ALL - KstatesFeature.MACROS, workspace.languageFeatures.value)
        assertEquals("Switch On Macros", registry.switchOf(KstatesFeature.MACROS).title)
        val codes = state.problems.state.value.groups.flatMap { group -> group.items.map { it.diagnostic.code } }
        assertTrue(codes.isNotEmpty() && codes.all { it == "kstates.feature-off" }, "$codes")
        assertFalse("feature.macros = on" in Files.readAllLines(root.resolve(ProjectSettings.FILE_NAME)))
    }
}
