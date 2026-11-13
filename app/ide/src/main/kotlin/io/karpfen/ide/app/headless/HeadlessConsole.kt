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

import java.io.PrintStream

/**
 * Where the headless mode writes. [output] is standard output. It carries what the commands report: the findings of a
 * validation and everything a run does. [errors] is standard error. It carries the mode's own messages, which start
 * with `karpfen-ide:`, and the usage after a wrong command line. The logging backend writes its warnings to standard
 * error as well.
 *
 * The default streams encode UTF-8 onto the process streams, whatever the encoding of the console is, so paths and
 * messages with characters outside ASCII keep their meaning. Both streams flush after every line, so a line shows as
 * soon as it is written, also when standard output goes to a pipe. Every function may be called from any thread.
 *
 * @param output standard output.
 * @param errors standard error.
 */
internal class HeadlessConsole(
    private val output: PrintStream = utf8(System.out),
    private val errors: PrintStream = utf8(System.err),
) {
    /** Writes [text] as one line to standard output. */
    fun line(text: String) {
        output.println(text)
    }

    /** Writes the mode's own message [text] to standard error, after `karpfen-ide:`. */
    fun message(text: String) {
        errors.println("$PREFIX $text")
    }

    /** Writes [text] to standard error as it is, such as the usage. */
    fun error(text: String) {
        errors.println(text)
    }

    private companion object {
        /** What starts every message of the mode itself. */
        const val PREFIX = "karpfen-ide:"

        /** A stream that encodes UTF-8 onto [stream] and flushes after every line. */
        fun utf8(stream: PrintStream): PrintStream = PrintStream(stream, true, Charsets.UTF_8)
    }
}
