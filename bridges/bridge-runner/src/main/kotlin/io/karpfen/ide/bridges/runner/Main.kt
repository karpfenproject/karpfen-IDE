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

/** The one-line usage that the headless bridge runner prints. The runner is a placeholder and starts no bridge yet. */
public const val USAGE: String =
    "Usage: karpfen-bridge-runner <bridge-config.kbridge.json>  (placeholder, no bridges are started yet)"

/**
 * Entry point of the headless bridge runner. Bridges are headless-first. They are plain Kotlin components with a
 * lifecycle that run from this command line on a robot or an edge box without the IDE. During development the IDE
 * configures, hosts and monitors them. For now the runner only prints [USAGE].
 */
public fun main() {
    println(USAGE)
}
