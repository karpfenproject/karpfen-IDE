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
package io.karpfen.ide.bridges.runner

/** One-line usage of the headless bridge runner (phase-0 stub: no bridge is started yet). */
public const val USAGE: String =
    "Usage: karpfen-bridge-runner <bridge-config.kbridge.json>  (phase-0 stub, no bridges are started)"

/**
 * Entry point of the headless bridge runner. Bridges are headless-first: plain Kotlin components with a lifecycle that
 * run from this command line on a robot or edge box without the IDE, while the IDE configures, hosts and monitors them
 * during development.
 */
public fun main() {
    println(USAGE)
}
