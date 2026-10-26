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

/**
 * The git submodules of the repository as `.gitmodules` records them: the directories of the checkouts of other
 * repositories, such as karpfen-dsl-tools, that the build uses but that are not part of this code base.
 *
 * The one reader of `.gitmodules` in the build. The build script of the architecture tests leaves these directories out
 * of the tests' inputs and hands the same list to the tests through [SYSTEM_PROPERTY], so the inputs and the files the
 * tests check always leave out the same directories.
 */
object GitSubmodules {
    /**
     * The system property through which a test task hands the submodule directories to its tests: the directories as
     * [encode] joins them.
     */
    const val SYSTEM_PROPERTY: String = "karpfen.submodules"

    /** Separates the directories in the value of [SYSTEM_PROPERTY]. */
    const val SEPARATOR: Char = ','

    private val PATH_ENTRY = Regex("""^\s*path\s*=\s*(.+?)\s*$""")

    /**
     * The `path` entries of a `.gitmodules` file's [text]: relative to the repository root, with '/' separators, without
     * trailing slashes and without empty entries.
     */
    fun parse(text: String): List<String> =
        text.lineSequence()
            .mapNotNull { line -> PATH_ENTRY.find(line)?.groupValues?.get(1) }
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toList()

    /**
     * The value of [SYSTEM_PROPERTY] that hands over [directories].
     *
     * @throws IllegalArgumentException if a directory holds the [SEPARATOR], which the value could not carry.
     */
    fun encode(directories: List<String>): String {
        val unsupported = directories.filter { SEPARATOR in it }
        require(unsupported.isEmpty()) { "submodule directories with a '$SEPARATOR' are not supported: $unsupported" }
        return directories.joinToString(SEPARATOR.toString())
    }
}
