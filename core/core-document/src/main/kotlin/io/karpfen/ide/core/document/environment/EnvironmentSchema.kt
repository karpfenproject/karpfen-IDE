package io.karpfen.ide.core.document.environment

/**
 * The schema of environment descriptors (`*.kenv.json`): which keys an object has, how they nest, what kind of value
 * each holds, which are required and what role each named file plays. [ROOT] is derived from the typed
 * [EnvironmentDescriptor] ([SerialShapes]), the one declaration of the descriptor's shape, and everything that knows
 * about descriptor keys reads them here:
 * - the descriptor reader of the workspace checks every descriptor against [ROOT] and then decodes it into the typed
 *   class;
 * - the environment language of this module highlights keys, file paths, object keys and event domains by [ROOT];
 * - the language services offer and explain keys and values by [ROOT].
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

    /** The descriptor object, the root of every descriptor. */
    public val ROOT: ValueShape.Record = SerialShapes.recordOf(EnvironmentDescriptor.serializer().descriptor)

    /**
     * The path of the key whose values name files of [role] in [schema], as messages name it: `metamodel`,
     * `stateMachines[].file`, `bridges[]`.
     *
     * @throws IllegalArgumentException if no key of [schema] names files of [role] (programming error).
     */
    public fun keyPathOf(role: DescriptorFileRole, schema: ValueShape.Record = ROOT): String =
        requireNotNull(pathIn(schema, role, prefix = "")) { "no key of the schema names files of role $role" }

    /** The path below [shape] (written after [prefix]) to the key whose values name files of [role], or null. */
    private fun pathIn(shape: ValueShape, role: DescriptorFileRole, prefix: String): String? = when (shape) {
        is ValueShape.FilePath -> prefix.takeIf { shape.role == role }
        is ValueShape.ListOf -> pathIn(shape.item, role, "$prefix[]")
        is ValueShape.Record -> shape.keys.firstNotNullOfOrNull { key ->
            pathIn(key.shape, role, if (prefix.isEmpty()) key.name else "$prefix.${key.name}")
        }
        else -> null
    }
}
