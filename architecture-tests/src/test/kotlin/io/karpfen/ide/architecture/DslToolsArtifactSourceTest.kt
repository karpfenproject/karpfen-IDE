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

import com.lemonappdev.konsist.api.Konsist
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In artifact mode the settings resolve karpfen-dsl-tools from GitHub Packages only: that repository is the exclusive
 * source of the group `io.karpfen`, so Maven Central and Google Maven, which the settings declare for every other
 * dependency and which Gradle asks first, are never asked for it, and no artifact of the same coordinates there can
 * take its place.
 */
class DslToolsArtifactSourceTest {

    @Test
    fun `the group of dsl-tools comes from GitHub Packages only`() {
        val settings = File(Konsist.projectRootPath, "settings.gradle.kts").readText()

        assertTrue(EXCLUSIVE_SOURCE.containsMatchIn(settings), "GitHub Packages must be the exclusive source of io.karpfen")
        assertFalse(SHARED_SOURCE.containsMatchIn(settings), "a content filter alone leaves io.karpfen to the other repositories")
    }

    private companion object {
        /** The GitHub Packages repository of dsl-tools as the exclusive source of the group `io.karpfen`. */
        val EXCLUSIVE_SOURCE = Regex(
            """exclusiveContent\s*\{\s*forRepository\s*\{\s*maven\s*\{[^}]*uri\(DSL_TOOLS_PACKAGES\)[^}]*}\s*}\s*""" +
                """filter\s*\{\s*includeGroup\("io\.karpfen"\)\s*}""",
        )

        /** A content filter that lets a repository serve `io.karpfen` without keeping the others from serving it. */
        val SHARED_SOURCE = Regex("""\bcontent\s*\{\s*includeGroup\("io\.karpfen"\)""")
    }
}
