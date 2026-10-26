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

import java.nio.file.Files
import java.nio.file.Path

/**
 * IDE-authored environment descriptors (`*.kenv.json`, which tie a metamodel, events, a model and state machines into
 * one modeling context) for the files of `karpfen-runtime/single_robot_example`, loaded from the test-fixture
 * resources below [RESOURCE_ROOT]. Each constant names one descriptor and the findings the workspace reports for it.
 * Shared by core-workspace (descriptor reading, explicit contexts), the CLI and the end-to-end tests, so nobody invents
 * descriptors of their own.
 */
public object DescriptorExamples {

    /** Classpath directory of the descriptors. */
    public const val RESOURCE_ROOT: String = "ide-examples/descriptors"

    /**
     * A valid descriptor with every top-level key (of the nested keys, it leaves out the runtime's `image`); it names
     * the four single-robot example files. No findings.
     */
    public const val VALID: String = "single_robot.kenv.json"

    /**
     * Valid JSON with the unknown key `tickDelay` (WARNING `descriptor-unknown-key`) and a `model` naming the missing
     * file `missing.kmodel` (ERROR `descriptor-missing-file`).
     */
    public const val UNKNOWN_KEY_AND_MISSING_FILE: String = "unknown_key_and_missing_file.kenv.json"

    /** Truncated JSON (ERROR `syntax-error` at the end of the text, no context). */
    public const val INVALID_JSON: String = "invalid_json.kenv.json"

    /** The example directory whose files the descriptors name. */
    public const val CONTEXT_DIRECTORY: String = "karpfen-runtime/single_robot_example"

    /** The object key the valid descriptor attaches the state machine to. */
    public const val ATTACHED_TO: String = "turtle"

    /** Every descriptor file name. */
    public fun names(): List<String> = listOf(VALID, UNKNOWN_KEY_AND_MISSING_FILE, INVALID_JSON)

    /**
     * The text of the descriptor [name] (one of [names]).
     * @throws IllegalArgumentException for an unknown name.
     */
    public fun text(name: String): String {
        require(name in names()) { "no descriptor example '$name'; known: ${names()}" }
        val stream = requireNotNull(DescriptorExamples::class.java.classLoader.getResourceAsStream("$RESOURCE_ROOT/$name")) {
            "test-fixture resource '$RESOURCE_ROOT/$name' not found on the classpath"
        }
        return stream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    /**
     * Writes the files of [CONTEXT_DIRECTORY] and the descriptor [name] into the directory [target] (an explicit
     * modeling context: the directory is covered by the descriptor) and returns the descriptor's path.
     */
    public fun copyExplicitContext(target: Path, name: String = VALID): Path {
        Files.createDirectories(target)
        Examples.files().filter { it.directory == CONTEXT_DIRECTORY }.forEach { file ->
            Files.writeString(target.resolve(file.fileName), file.text)
        }
        return target.resolve(name).also { Files.writeString(it, text(name)) }
    }
}
