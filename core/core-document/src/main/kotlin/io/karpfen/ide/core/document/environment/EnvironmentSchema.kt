package io.karpfen.ide.core.document.environment

/**
 * The schema of environment descriptors (`*.kenv.json`), the one home of their keys: which keys an object has, how they
 * nest, what kind of value each holds, which are required and what role each named file plays. Everything that knows
 * about descriptor keys reads them here:
 * - the descriptor reader of the workspace checks every descriptor against [ROOT] and decodes it into its typed
 *   `EnvironmentDescriptor`, whose serial names are the key constants below (a test of the workspace checks that the
 *   typed class has exactly the shape of [ROOT]);
 * - the environment language of this module highlights keys, file paths, object keys and event domains by [ROOT];
 * - the language services offer and explain keys and values by [ROOT];
 * - the file templates write new descriptors through the typed class.
 *
 * The key constants exist so that the typed class can name its keys (`@SerialName` needs constants); every other
 * reader walks [ROOT]. A descriptor looks like this:
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

    /** The key of the domain metamodel. */
    public const val METAMODEL: String = "metamodel"

    /** The key of the event metamodel. */
    public const val EVENTS: String = "events"

    /** The key of the model. */
    public const val MODEL: String = "model"

    /** The key of the list of state machines. */
    public const val STATE_MACHINES: String = "stateMachines"

    /** The key of a state machine entry's file. */
    public const val FILE: String = "file"

    /** The key of the object a state machine entry runs attached to. */
    public const val ATTACHED_TO: String = "attachedTo"

    /** The key of the runtime's tick delay. */
    public const val TICK_DELAY_MS: String = "tickDelayMs"

    /** The key of the events' time to live. */
    public const val EVENT_TTL_MS: String = "eventTtlMs"

    /** The key of the observation settings. */
    public const val OBSERVE: String = "observe"

    /** The key of the observed objects' pattern. */
    public const val OBJECTS: String = "objects"

    /** The pattern of [OBJECTS] that observes every object; completion suggests it, and new descriptors use it. */
    public const val EVERY_OBJECT: String = "*"

    /** The key of the observed event domains. */
    public const val DOMAINS: String = "domains"

    /** The key of the runtime settings. */
    public const val RUNTIME: String = "runtime"

    /** The key of the runtime's container image. */
    public const val IMAGE: String = "image"

    /** The key of the runtime's extra Python packages. */
    public const val PYTHON_PACKAGES: String = "pythonPackages"

    /** The key of the bridge configurations. */
    public const val BRIDGES: String = "bridges"

    /** One entry of [STATE_MACHINES]. */
    private val STATE_MACHINE_ENTRY = ValueShape.Record(
        listOf(
            DescriptorKey(
                FILE, ValueShape.FilePath(DescriptorFileRole.STATE_MACHINE), isRequired = true, isNullable = false,
                "The state machine: a .kstates file, relative to this descriptor.",
            ),
            DescriptorKey(
                ATTACHED_TO, ValueShape.ObjectKey, isRequired = true, isNullable = false,
                "The key of the model object the state machine runs attached to; its type should be the type the " +
                    "state machine names after ATTACHED TO.",
            ),
        ),
    )

    /** The value of [OBSERVE]. */
    private val OBSERVATION = ValueShape.Record(
        listOf(
            DescriptorKey(
                OBJECTS, ValueShape.Text(listOf(EVERY_OBJECT)), isRequired = true, isNullable = false,
                "The keys of the objects the IDE observes while the environment runs, as a pattern; \"$EVERY_OBJECT\" " +
                    "observes every object.",
            ),
            DescriptorKey(
                DOMAINS, ValueShape.ListOf(ValueShape.EventDomain), isRequired = false, isNullable = false,
                "The event domains the IDE observes while the environment runs, the domains of EVENT(\"domain\", " +
                    "\"name\") in the state machines.",
            ),
        ),
    )

    /** The value of [RUNTIME]. */
    private val RUNTIME_SETTINGS = ValueShape.Record(
        listOf(
            DescriptorKey(
                IMAGE, ValueShape.Text(), isRequired = false, isNullable = true,
                "The container image the runtime runs in, such as ghcr.io/karpfenproject/karpfen-runtime:0.x.",
            ),
            DescriptorKey(
                PYTHON_PACKAGES, ValueShape.ListOf(ValueShape.Text(listOf("numpy"))), isRequired = false, isNullable = false,
                "Extra Python packages the runtime installs for the EVAL blocks of the state machines, such as numpy.",
            ),
        ),
    )

    /** The descriptor object, the root of every descriptor. */
    public val ROOT: ValueShape.Record = ValueShape.Record(
        listOf(
            DescriptorKey(
                METAMODEL, ValueShape.FilePath(DescriptorFileRole.METAMODEL), isRequired = true, isNullable = false,
                "The domain metamodel of the environment: a .kmeta file, relative to this descriptor.",
            ),
            DescriptorKey(
                EVENTS, ValueShape.FilePath(DescriptorFileRole.EVENTS), isRequired = false, isNullable = true,
                "The event metamodel that declares the events of the state machines, usually EVENTS.kmeta.",
            ),
            DescriptorKey(
                MODEL, ValueShape.FilePath(DescriptorFileRole.MODEL), isRequired = true, isNullable = false,
                "The model the runtime starts from: a .kmodel file of the domain metamodel, relative to this descriptor.",
            ),
            DescriptorKey(
                STATE_MACHINES, ValueShape.ListOf(STATE_MACHINE_ENTRY), isRequired = false, isNullable = false,
                "The state machines of the environment, each running attached to one object of the model.",
            ),
            DescriptorKey(
                TICK_DELAY_MS, ValueShape.WholeNumber(listOf(50, 100, 500)), isRequired = false, isNullable = true,
                "The delay between two ticks of the runtime's engine, in milliseconds.",
            ),
            DescriptorKey(
                EVENT_TTL_MS, ValueShape.WholeNumber(listOf(1_000, 3_000, 10_000)), isRequired = false, isNullable = true,
                "How long an event stays available to the state machines, in milliseconds.",
            ),
            DescriptorKey(
                OBSERVE, OBSERVATION, isRequired = false, isNullable = true,
                "What the IDE observes while the environment runs: objects and event domains.",
            ),
            DescriptorKey(
                RUNTIME, RUNTIME_SETTINGS, isRequired = false, isNullable = true,
                "How the runtime runs the environment: its container image and extra Python packages.",
            ),
            DescriptorKey(
                BRIDGES, ValueShape.ListOf(ValueShape.FilePath(DescriptorFileRole.BRIDGE)), isRequired = false, isNullable = false,
                "Bridge configurations (.kbridge.json) that connect the environment to MQTT or ROS; the IDE does not " +
                    "interpret them yet.",
            ),
        ),
    )

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
