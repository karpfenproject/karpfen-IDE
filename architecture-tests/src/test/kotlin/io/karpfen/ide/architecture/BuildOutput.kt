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

/**
 * The one rule by which the repository tells build output from sources. Build output is a directory named `build`
 * outside the source directories, which means that no `src` segment comes before it. These are the `build` directories
 * that Gradle writes for the root, for build-logic and for every module.
 *
 * A source package named `build`, such as `core/core-text/src/main/kotlin/io/karpfen/ide/core/text/build`, is sources
 * like any other package. The documentation style rules guard it, the package layout rules look into it, and git keeps
 * it. Git keeps it because the `.gitignore` names only the `build` directories that Gradle writes, anchored at the
 * root. The inputs of the architecture tests, declared in their build script, leave out the same directories.
 *
 * Paths are relative to the repository root and use '/' as the separator. A leading separator, as Konsist writes
 * project paths, is ignored.
 */
internal object BuildOutput {

    /** The name of the directories that hold build output. */
    private const val BUILD = "build"

    /** The name of the directory that holds the source sets of a module. */
    private const val SOURCES = "src"

    /** True for the directory at [relativePath] if it holds build output, such as `core/core-text/build`. */
    fun isBuildDirectory(relativePath: String): Boolean {
        val segments = segmentsOf(relativePath)
        return segments.lastOrNull() == BUILD && SOURCES !in segments.dropLast(1)
    }

    /**
     * True for the file at [relativePath] if it lies inside build output. That is the case when a directory named
     * `build` comes before the first `src` segment of its path, or when its path has a `build` directory and no `src`.
     */
    fun contains(relativePath: String): Boolean {
        val directories = segmentsOf(relativePath).dropLast(1)
        val build = directories.indexOf(BUILD)
        val sources = directories.indexOf(SOURCES)
        return build >= 0 && (sources < 0 || build < sources)
    }

    /** The segments of [relativePath], without the empty segment of a leading separator. */
    private fun segmentsOf(relativePath: String): List<String> = relativePath.removePrefix("/").split('/')
}
