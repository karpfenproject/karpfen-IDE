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
package io.karpfen.ide.ui.workbench.templates

import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.workspace.analysis.AttachmentCandidate
import io.karpfen.ide.core.workspace.environment.DescriptorPaths
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * The content of a new environment descriptor `*.kenv.json`, as [FileTemplates] documents it. It is pure.
 *
 * The descriptor names the files of the directory's current context. It names them the way descriptors name files,
 * through [DescriptorPaths.relative], so creating the descriptor keeps them in one context. Each state machine is
 * attached to the object that [AttachmentCandidate.rank] proposes first. That is the same rule the descriptor
 * completion ranks by. If no object is proposed, the machine is attached to [PLACEHOLDER_OBJECT]. When the state
 * machines use event domains, an `observe` section watches every object through [EnvironmentSchema.EVERY_OBJECT] and
 * lists those domains. Without a context, the descriptor names files after itself.
 *
 * The descriptor is the typed `EnvironmentDescriptor`, encoded with kotlinx.serialization. The environment schema is
 * derived from that class. Its keys are therefore the schema's keys, every name is a correctly escaped JSON string,
 * and keys left at their defaults are omitted.
 */
internal object EnvironmentTemplate {

    /** The object key used when the context knows no object. */
    const val PLACEHOLDER_OBJECT: String = "object"

    /**
     * Pretty-printing JSON that leaves out the keys at their defaults, so the new descriptor reads well in any editor.
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    /**
     * The descriptor of a new environment file [baseName] for [context], ending in a newline. [baseName] has no
     * suffix.
     */
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

    /**
     * The key of the object that the state machine [file] runs attached to, as [FileTemplates] describes. Falls back
     * to [PLACEHOLDER_OBJECT].
     */
    fun attachedTo(file: Path, context: TemplateContext): String =
        AttachmentCandidate.rank(context.objects, context.stateMachineTypes[file]).firstOrNull()?.modelObject?.key
            ?: PLACEHOLDER_OBJECT
}
