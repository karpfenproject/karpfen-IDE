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
package io.karpfen.ide.core.document.environment

/**
 * The schema of environment descriptors, the `*.kenv.json` files. It says which keys an object has, how they nest,
 * what kind of value each holds, which keys are required and what role each named file plays. [SerialShapes] derives
 * [ROOT] from the typed [EnvironmentDescriptor], which is the one declaration of the descriptor's shape. Everything
 * that knows about descriptor keys reads them here.
 * - The descriptor reader of the workspace checks every descriptor against [ROOT] and then decodes it into the typed
 *   class.
 * - The environment language of this module highlights keys, file paths, object keys and event domains by [ROOT].
 * - The language services offer and explain keys and values by [ROOT].
 *
 * A descriptor looks like this:
 *
 * ```json
 * {
 *   "metamodel": "cleaning_robot.kmeta",
 *   "events": "EVENTS.kmeta",
 *   "model": "cleaning_robot.kmodel",
 *   "stateMachines": [ { "file": "cleaning_robot.kstates", "attachedTo": "turtle" } ],
 *   "tickDelayMs": 50, "eventTtlMs": 3000,
 *   "observe": { "objects": "*", "domains": ["public"] },
 *   "runtime": { "image": "ghcr.io/karpfenproject/karpfen-runtime:0.x", "pythonPackages": ["numpy"] },
 *   "bridges": [ "mqtt-sim.kbridge.json" ]
 * }
 * ```
 */
public object EnvironmentSchema {

    /**
     * The pattern of `observe.objects` that observes every object. Completion suggests it, and new descriptors use it.
     */
    public const val EVERY_OBJECT: String = "*"

    /** The descriptor object, the root of every descriptor. */
    public val ROOT: ValueShape.Record = SerialShapes.recordOf(EnvironmentDescriptor.serializer().descriptor)

    /**
     * The path of the key whose values name files of [role] in [schema], as messages name it. Examples are
     * `metamodel`, `stateMachines[].file` and `bridges[]`.
     *
     * @throws IllegalArgumentException if no key of [schema] names files of [role], which is a programming error.
     */
    public fun keyPathOf(role: DescriptorFileRole, schema: ValueShape.Record = ROOT): String =
        requireNotNull(pathIn(schema, role, prefix = "")) { "no key of the schema names files of role $role" }

    /**
     * The path below [shape] to the key whose values name files of [role], written after [prefix]. Null if there is no
     * such key.
     */
    private fun pathIn(shape: ValueShape, role: DescriptorFileRole, prefix: String): String? = when (shape) {
        is ValueShape.FilePath -> prefix.takeIf { shape.role == role }
        is ValueShape.ListOf -> pathIn(shape.item, role, "$prefix[]")
        is ValueShape.Record -> shape.keys.firstNotNullOfOrNull { key ->
            pathIn(key.shape, role, if (prefix.isEmpty()) key.name else "$prefix.${key.name}")
        }
        else -> null
    }
}
