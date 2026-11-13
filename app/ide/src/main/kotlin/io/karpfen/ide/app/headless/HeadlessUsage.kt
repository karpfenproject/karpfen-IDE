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
package io.karpfen.ide.app.headless

/** The usage of the IDE's command line, which the headless mode prints on request and after a usage error. */
internal object HeadlessUsage {
    /** The usage, one form per line. */
    val TEXT: String = """
        Usage:
          karpfen-ide [<folder>]
          karpfen-ide validate <folder>
          karpfen-ide run <folder> [--descriptor <file>] [--bridge <file>]... [--capture] [--verbose]
    """.trimIndent()
}
