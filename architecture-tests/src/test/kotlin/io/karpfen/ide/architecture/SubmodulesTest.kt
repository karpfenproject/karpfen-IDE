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
package io.karpfen.ide.architecture

import io.karpfen.ide.buildlogic.GitSubmodules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The git submodules that the build reads from `.gitmodules` reach the tests, and their sources stay out of the Konsist
 * scope and of the files the documentation style rules read, while the repository's own sources stay in.
 */
class SubmodulesTest {

    @Test
    fun `the tests get every submodule that gitmodules records`() {
        val recorded = GitSubmodules.parse(RootFiles.read(RootFiles.GITMODULES))

        assertTrue(recorded.isNotEmpty(), "no submodule recorded; this check would be vacuous")
        assertEquals(recorded, Submodules.paths)
    }

    @Test
    fun `the dsl-tools submodule is recorded and its directory is recognized`() {
        assertTrue("karpfen-dsl-tools" in Submodules.paths, "expected the karpfen-dsl-tools submodule in .gitmodules")
        assertTrue(Submodules.contains("karpfen-dsl-tools/src/main/kotlin/A.kt"))
        assertTrue(Submodules.contains("/karpfen-dsl-tools/build.gradle"))
        assertFalse(Submodules.contains("karpfen-dsl-tools-notes/readme.md"))
        assertFalse(Submodules.contains("core/core-text/src/main/kotlin/A.kt"))
    }

    @Test
    fun `no submodule file is part of the scanned code base`() {
        val konsistPaths = KarpfenModules.sourceFiles.map { it.projectPath.replace('\\', '/') } +
            KarpfenModules.buildToolingTestFiles.map { it.projectPath.replace('\\', '/') }
        assertTrue(konsistPaths.isNotEmpty(), "the Konsist scope is empty; this check would be vacuous")
        assertEquals(emptyList(), konsistPaths.filter(Submodules::contains))
        val documentedPaths = (DocumentedFiles.all + DocumentedFiles.documents).map { it.relativePath }
        assertTrue(documentedPaths.isNotEmpty(), "no documented files; this check would be vacuous")
        assertEquals(emptyList(), documentedPaths.filter(Submodules::contains))
    }
}
