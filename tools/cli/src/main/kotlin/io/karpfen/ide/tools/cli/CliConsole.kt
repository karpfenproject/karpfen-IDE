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
package io.karpfen.ide.tools.cli

import java.io.PrintStream

/**
 * The console of the command-line tool. [output] is standard output. It carries the report and the usage of `help`
 * and nothing else, so a JSON report stays machine-readable. [errorOutput] is standard error. It carries the tool's
 * own messages and, through the logging backend, the log. The log holds the tool's warnings and, with `--verbose`,
 * what dsl-tools logs. Nothing else prints to standard output while a folder is validated, which a test of
 * `karpfen validate` on the examples proves. So the report needs no guard around the analysis.
 *
 * The default streams encode UTF-8 onto the process streams captured at construction, whatever the console's encoding
 * is. That encoding is `stdout.encoding`, which is US-ASCII under `LANG=C` in containers and minimal CI runners. So
 * paths and messages with non-ASCII characters keep naming their files instead of turning into `?`.
 */
internal class CliConsole(
    val output: PrintStream = utf8(System.out),
    val errorOutput: PrintStream = utf8(System.err),
) {
    private companion object {
        /** A stream that encodes UTF-8 onto [stream]. Bytes pass a `PrintStream` unchanged, whatever its encoding. */
        fun utf8(stream: PrintStream): PrintStream = PrintStream(stream, true, Charsets.UTF_8)
    }
}
