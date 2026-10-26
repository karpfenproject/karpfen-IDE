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
package io.karpfen.ide.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Covers [GitSubmodules]: the `path` entries of a `.gitmodules` text are normalized to '/' separators without trailing
 * slashes and without empty entries, and the directories are handed over joined, which a directory with the separator
 * cannot be.
 */
class GitSubmodulesTest {

    @Test
    fun `path entries are read from a gitmodules text and normalized`() {
        val text = """
            [submodule "karpfen-dsl-tools"]
            	path = karpfen-dsl-tools
            	url = https://github.com/karpfenproject/karpfen-dsl-tools.git
            [submodule "nested"]
                path = external/nested/
            [submodule "windows"]
                path = external\windows
            [submodule "root"]
                path = /
        """.trimIndent()

        assertEquals(listOf("karpfen-dsl-tools", "external/nested", "external/windows"), GitSubmodules.parse(text))
    }

    @Test
    fun `the directories are handed over joined, and no entry records no submodule`() {
        val text = "[submodule \"a\"]\n\tpath = a\n[submodule \"b\"]\n\tpath = libs/b\n"

        assertEquals("a,libs/b", GitSubmodules.encode(GitSubmodules.parse(text)))
        assertEquals(emptyList<String>(), GitSubmodules.parse(""))
        assertThrows(IllegalArgumentException::class.java) { GitSubmodules.encode(listOf("a,b")) }
    }
}
