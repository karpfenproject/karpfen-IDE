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

/**
 * The repository an example file was copied from.
 *
 * @property directoryName the first path segment of the examples copied from it.
 * @property commit the commit they were copied at.
 */
public enum class ExampleOrigin(public val directoryName: String, public val commit: String) {
    /** The folder `examples/` of karpfenproject/karpfen-runtime. */
    KARPFEN_RUNTIME("karpfen-runtime", "d4c2059"),

    /** The folder `example/` of karpfenproject/karpfen-dsl-tools. */
    KARPFEN_DSL_TOOLS("karpfen-dsl-tools", "859a95d"),
}

/**
 * One example file from the test fixtures.
 *
 * @property path the path below the fixture root, separated by `/`, such as
 *   `karpfen-runtime/single_robot_example/EVENTS.kmeta`.
 * @property language the language of the file, taken from its extension.
 * @property text the unmodified content of the file, decoded as UTF-8. The examples use `\n` separators.
 */
public data class ExampleFile(public val path: String, public val language: KarpfenLanguage, public val text: String) {

    /** The origin repository, named by the first path segment. */
    public val origin: ExampleOrigin
        get() = ExampleOrigin.entries.first { path.startsWith(it.directoryName + "/") }

    /** The file name, such as `EVENTS.kmeta`. */
    public val fileName: String get() = path.substringAfterLast('/')

    /** The directory below the fixture root, such as `karpfen-runtime/single_robot_example`. */
    public val directory: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")

    /** True for an event metamodel, which is a file named `EVENTS.kmeta` in any letter case. */
    public val isEventsMetamodel: Boolean get() = fileName.equals("EVENTS.kmeta", ignoreCase = true)

    /** The number of lines separated by `\n`. A trailing newline adds an empty last line. */
    public val lineCount: Int get() = text.count { it == '\n' } + 1

    override fun toString(): String = path
}

/**
 * The example files of one directory. They form one implicit modeling context, which is a directory not covered by an
 * environment descriptor. Such a context holds the domain metamodel, the optional event metamodel, models and state
 * machines.
 *
 * @property directory the directory below the fixture root.
 * @property files the example files of [directory], sorted by path.
 */
public data class ExampleContext(public val directory: String, public val files: List<ExampleFile>) {

    /** The domain metamodel, or null. It is the alphabetically first `.kmeta` file that is not `EVENTS.kmeta`. */
    public val metamodel: ExampleFile?
        get() = files.filter { it.language == KarpfenLanguage.KMETA && !it.isEventsMetamodel }.minByOrNull { it.fileName }

    /** The event metamodel `EVENTS.kmeta`, or null. */
    public val events: ExampleFile? get() = files.firstOrNull { it.isEventsMetamodel }

    /** The `.kmodel` files. */
    public val models: List<ExampleFile> get() = files.filter { it.language == KarpfenLanguage.KMODEL }

    /** The `.kstates` files. */
    public val stateMachines: List<ExampleFile> get() = files.filter { it.language == KarpfenLanguage.KSTATES }

    override fun toString(): String = directory
}
