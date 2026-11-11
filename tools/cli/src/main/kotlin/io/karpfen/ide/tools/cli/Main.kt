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

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** One-line usage of the karpfen command-line tool. */
public const val USAGE: String = "Usage: karpfen validate <dir> [--format text|json] [--verbose]"

/**
 * Entry point of the karpfen command-line tool, whose start script is `karpfen`. It runs the command line and exits
 * with its status, as [ExitCodes] lists them. The status is 0 when all is clean, 1 when `validate` found an ERROR, 2
 * for invalid arguments and 3 on an internal failure.
 */
public fun main(args: Array<String>) {
    val status = runBlocking { KarpfenCli().execute(args.asList()) }
    exitProcess(status)
}
