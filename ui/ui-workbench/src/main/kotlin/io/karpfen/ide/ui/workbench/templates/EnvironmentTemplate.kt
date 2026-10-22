package io.karpfen.ide.ui.workbench.templates

import io.karpfen.ide.core.workspace.environment.DescriptorPaths
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptor
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Content of a new environment descriptor `*.kenv.json` (documented on [FileTemplates]): the files of the directory's
 * current context, named as descriptors name files ([DescriptorPaths.relative]), so creating the descriptor keeps them
 * in one context; each state machine attached to the first object of its `ATTACHED TO` type, else to the first object,
 * else to [PLACEHOLDER_OBJECT]; when the state machines use event domains, an `observe` section that watches every
 * object ([OBSERVE_ALL]) and those domains. Without a context the descriptor names files after itself.
 *
 * The descriptor is the typed `EnvironmentDescriptor` encoded with kotlinx.serialization, so its keys are the ones of
 * the environment schema, every name is a correctly escaped JSON string, and keys left at their defaults are omitted.
 * Pure.
 */
internal object EnvironmentTemplate {

    /** The object key used when the context knows no object. */
    const val PLACEHOLDER_OBJECT: String = "object"

    /** The object pattern that observes every object. */
    const val OBSERVE_ALL: String = "*"

    /** Pretty-printing JSON without the keys left at their defaults, so the new descriptor reads well in any editor. */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    /** The descriptor of a new environment file [baseName] (without suffix) for [context], ending in a newline. */
    fun content(baseName: String, context: TemplateContext): String {
        val modeling = context.context
        val relative = { path: Path -> DescriptorPaths.relative(context.directory, path) }
        val machines = modeling?.stateMachines?.map { it.file } ?: listOf(context.directory.resolve("$baseName.kstates"))
        val descriptor = EnvironmentDescriptor(
            metamodel = modeling?.metamodel?.let(relative) ?: "$baseName.kmeta",
            events = modeling?.events?.let(relative),
            model = modeling?.models?.firstOrNull()?.let(relative) ?: "$baseName.kmodel",
            stateMachines = machines.map { EnvironmentDescriptor.StateMachineEntry(relative(it), attachedTo(it, context)) },
            observe = context.eventDomains.takeIf { it.isNotEmpty() }?.let { EnvironmentDescriptor.ObserveSpec(OBSERVE_ALL, it) },
        )
        return json.encodeToString(EnvironmentDescriptor.serializer(), descriptor) + "\n"
    }

    /** The key of the object the state machine [file] runs attached to (see [FileTemplates]). */
    fun attachedTo(file: Path, context: TemplateContext): String {
        val candidates = context.objects.filter { it.key.isNotBlank() }
        val type = context.stateMachineTypes[file]
        return candidates.firstOrNull { type != null && it.typeName == type }?.key
            ?: candidates.firstOrNull()?.key
            ?: PLACEHOLDER_OBJECT
    }
}
