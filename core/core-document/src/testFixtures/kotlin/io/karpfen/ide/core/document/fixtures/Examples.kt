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
package io.karpfen.ide.core.document.fixtures

import io.karpfen.ide.core.document.KarpfenLanguage
import java.nio.file.Files
import java.nio.file.Path

/**
 * The example files copied from karpfen-runtime and karpfen-dsl-tools, loaded from the test-fixture resources below
 * [RESOURCE_ROOT]. They are unmodified copies, and [ExampleOrigin] names the repository and the commit of each. The
 * file list comes from `examples/index.txt`, so loading works from a directory and from the test-fixtures jar alike.
 *
 * The loaded files are immutable constants, and every function is deterministic.
 */
public object Examples {

    /** The classpath directory of the examples. */
    public const val RESOURCE_ROOT: String = "examples"

    /** The classpath location of the file list. */
    public const val INDEX_RESOURCE: String = "$RESOURCE_ROOT/index.txt"

    /** Every example file, loaded on first use. */
    private val all: List<ExampleFile> by lazy { load() }

    /** Every example file, sorted by path. */
    public fun files(): List<ExampleFile> = all

    /** The example files of [language], sorted by path. */
    public fun files(language: KarpfenLanguage): List<ExampleFile> = all.filter { it.language == language }

    /**
     * The example file at [path] below the fixture root, such as `karpfen-runtime/single_robot_example/EVENTS.kmeta`.
     *
     * @throws IllegalArgumentException if there is no such example.
     */
    public fun file(path: String): ExampleFile =
        requireNotNull(all.firstOrNull { it.path == path }) { "no example file '$path'; known: ${all.map { it.path }}" }

    /** The example files grouped by directory, one [ExampleContext] per directory, sorted by directory. */
    public fun contexts(): List<ExampleContext> =
        all.groupBy { it.directory }.toSortedMap().map { (directory, files) -> ExampleContext(directory, files) }

    /**
     * Writes every example file below [target] in the layout `<origin>/<example>/` and returns [target]. Workspace,
     * CLI and application tests use it when they need a real folder.
     */
    public fun copyTo(target: Path): Path {
        for (file in all) {
            val destination = target.resolve(file.path)
            Files.createDirectories(destination.parent)
            Files.writeString(destination, file.text)
        }
        return target
    }

    /** The paths listed in the index, in index order. Comments and blank lines are skipped. */
    public fun indexedPaths(): List<String> =
        readResource(INDEX_RESOURCE).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Reads every file of the index, sorted by path. */
    private fun load(): List<ExampleFile> =
        indexedPaths().sorted().map { path ->
            val language = requireNotNull(KarpfenLanguage.fromFileName(path.substringAfterLast('/'))) {
                "$INDEX_RESOURCE lists '$path', which is not a karpfen file"
            }
            ExampleFile(path, language, readResource("$RESOURCE_ROOT/$path"))
        }

    /** The UTF-8 text of the classpath resource [name]. */
    private fun readResource(name: String): String {
        val stream = requireNotNull(Examples::class.java.classLoader.getResourceAsStream(name)) {
            "test-fixture resource '$name' not found on the classpath"
        }
        return stream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }
}
