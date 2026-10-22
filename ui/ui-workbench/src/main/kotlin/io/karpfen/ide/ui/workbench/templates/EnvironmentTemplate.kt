package io.karpfen.ide.ui.workbench.templates

import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.workspace.analysis.AttachmentCandidate
import io.karpfen.ide.core.workspace.environment.DescriptorPaths
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Content of a new environment descriptor `*.kenv.json` (documented on [FileTemplates]): the files of the directory's
 * current context, named as descriptors name files ([DescriptorPaths.relative]), so creating the descriptor keeps them
 * in one context; each state machine attached to the object [AttachmentCandidate.rank] proposes first, the rule the
 * descriptor completion ranks by, else to [PLACEHOLDER_OBJECT]; when the state machines use event domains, an `observe`
 * section that watches every object ([EnvironmentSchema.EVERY_OBJECT]) and those domains. Without a context the
 * descriptor names files after itself.
 *
 * The descriptor is the typed `EnvironmentDescriptor` encoded with kotlinx.serialization, the class the environment
 * schema is derived from, so its keys are the schema's, every name is a correctly escaped JSON string, and keys left
 * at their defaults are omitted.
 * Pure.
 */
internal object EnvironmentTemplate {

    /** The object key used when the context knows no object. */
    const val PLACEHOLDER_OBJECT: String = "object"

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
            observe = context.eventDomains.takeIf { it.isNotEmpty() }?.let {
                EnvironmentDescriptor.ObserveSpec(EnvironmentSchema.EVERY_OBJECT, it)
            },
        )
        return json.encodeToString(EnvironmentDescriptor.serializer(), descriptor) + "\n"
    }

    /** The key of the object the state machine [file] runs attached to (see [FileTemplates]). */
    fun attachedTo(file: Path, context: TemplateContext): String =
        AttachmentCandidate.rank(context.objects, context.stateMachineTypes[file]).firstOrNull()?.modelObject?.key
            ?: PLACEHOLDER_OBJECT
}
