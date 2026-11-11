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
package io.karpfen.ide.runtime.protocol.source

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The checkout of the karpfen-runtime submodule whose sources the anchor tests read. It holds the runtime with IDE
 * support that the client is written against. The `test` task of runtime-protocol hands its absolute path to the tests
 * as the system property [PROPERTY]. The task also declares the server and engine sources as inputs, so moving the
 * submodule reruns the tests.
 *
 * @property root the runtime's directory, the one with `src/` and `execution-engine/`.
 */
public class RuntimeSourceCheckout(public val root: Path) {

    /**
     * The text of the runtime file at [relativePath] below [root], such as
     * `src/main/kotlin/io/karpfen/server/HTTPRoutes.kt`.
     *
     * @throws IllegalStateException naming the file and the submodule if the checkout has no such file.
     */
    public fun text(relativePath: String): String {
        val file = root.resolve(relativePath)
        check(Files.isRegularFile(file)) {
            "the karpfen-runtime checkout at $root has no $relativePath; is the submodule at the branch with IDE support?"
        }
        return Files.readString(file)
    }

    /** The text of the server file at [relativePath] below the server's sources [SERVER_SOURCES]. */
    public fun server(relativePath: String): String = text("$SERVER_SOURCES/$relativePath")

    /** The text of the engine file at [relativePath] below the engine's sources [ENGINE_SOURCES]. */
    public fun engine(relativePath: String): String = text("$ENGINE_SOURCES/$relativePath")

    public companion object {
        /** The system property that names the checkout. */
        public const val PROPERTY: String = "karpfen.runtime.checkout"

        /** The server's Kotlin sources below the checkout. */
        public const val SERVER_SOURCES: String = "src/main/kotlin"

        /** The engine's Kotlin sources below the checkout. */
        public const val ENGINE_SOURCES: String = "execution-engine/src/main/kotlin"

        /**
         * The checkout named by the system property [PROPERTY].
         *
         * @throws IllegalStateException with a message naming the karpfen-runtime submodule if the property is
         *   missing or names a directory without the runtime's server sources, such as a submodule that is not checked
         *   out.
         */
        public fun fromSystemProperty(): RuntimeSourceCheckout {
            val path = System.getProperty(PROPERTY)
            checkNotNull(path) {
                "the system property $PROPERTY is not set: runtime-protocol's test task sets it to the karpfen-runtime " +
                    "submodule named in .gitmodules"
            }
            val root = Paths.get(path)
            check(Files.isDirectory(root.resolve(SERVER_SOURCES))) {
                "the karpfen-runtime submodule at $root is not checked out; run git submodule update --init karpfen-runtime"
            }
            return RuntimeSourceCheckout(root)
        }
    }
}
