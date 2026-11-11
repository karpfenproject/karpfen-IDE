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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.workspace.analysis.AttachmentCandidate
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.analysis.ModelObject
import io.karpfen.ide.core.workspace.context.ModelingContext
import java.nio.file.Path

/**
 * The kinds of files that the project tree can create through the New entries of its context menu. A kind names the
 * [language] of its files. It takes the file name suffix and the name it shows from that language. A created file
 * therefore always has a name by which `KarpfenLanguage.fromFileName` recognizes the language.
 *
 * @property language the karpfen language of the files of this kind.
 */
public enum class FileTemplateKind(public val language: KarpfenLanguage) {
    /** A metamodel with one example type. */
    KMETA(KarpfenLanguage.KMETA),

    /** A model with one root object. */
    KMODEL(KarpfenLanguage.KMODEL),

    /** A state machine with one initial state. */
    KSTATES(KarpfenLanguage.KSTATES),

    /** An environment descriptor, `*.kenv.json`. */
    ENVIRONMENT(KarpfenLanguage.ENVIRONMENT),
    ;

    /**
     * The file name suffix including its dot, such as `.kmeta` or `.kenv.json`. It is the language's extension after a
     * dot.
     */
    public val suffix: String get() = ".${language.extension}"

    /**
     * The kind's name in menus and dialogs. It is the language's display name followed by "File", such as "KMeta File".
     */
    public val displayName: String get() = "${language.displayName} File"
}

/**
 * What a template knows about the place where the new file goes. `WorkbenchState.createFile` builds it from
 * `Workspace.contexts` and the symbols of `Workspace.analysis`. The symbols come from [ContextSymbols], which reads the
 * syntax indexes owned by the IDE and never the models that dsl-tools read. The context is therefore known even while
 * a metamodel has errors.
 *
 * @property directory the target directory.
 * @property context the modeling context that the directory forms or is covered by before the file is created, or
 *   null. It is the implicit context of the directory or the explicit context of its descriptor.
 * @property typeNames the types of that context's domain metamodel, in declaration order. Empty if unknown.
 * @property objects the objects with a key in that context's first model, nested ones included, in document order.
 *   Empty if unknown.
 * @property stateMachineTypes the `ATTACHED TO` type of each state machine of the context, keyed by path. A machine
 *   whose type is unknown is absent.
 * @property eventDomains the event domains that the context's state machines use through `EVENT("domain", …)`. They
 *   are sorted and contain no repetitions.
 */
public data class TemplateContext(
    public val directory: Path,
    public val context: ModelingContext? = null,
    public val typeNames: List<String> = emptyList(),
    public val objects: List<ModelObject> = emptyList(),
    public val stateMachineTypes: Map<Path, String> = emptyMap(),
    public val eventDomains: List<String> = emptyList(),
)

/**
 * The content of new files. Generated karpfen sources must tokenize and parse without diagnostics. Where the
 * [TemplateContext] allows it, they must also read without findings in their context.
 *
 * A new KMeta file holds one example type named after the file.
 *
 * A new KModel file holds one root object. Its type is the context's first domain type from `TemplateContext.typeNames`
 * when that is known.
 *
 * A new KStates file is `ATTACHED TO` the context's first domain type when that is known. It has one initial state.
 *
 * A new environment descriptor names the files of the directory's current context. These are the metamodel, the
 * events, the first model and the state machines. The paths are relative to the directory. Creating the descriptor
 * therefore keeps the directory's files in one context instead of turning them into loose files. Each state machine is
 * attached to the first object that the ranking rule of [AttachmentCandidate.rank] proposes for it. That is the first
 * object of its `ATTACHED TO` type, otherwise the first object, and otherwise the placeholder `"object"`. In the
 * single_robot_example, for example, `cleaning_robot.kstates` is attached to `"turtle"`, which is the Robot and not the
 * root Room. When the state machines use event domains, the descriptor observes every object and those domains.
 * Without a context, it names files after the descriptor. They are reported as `descriptor-missing-file` until they
 * are created. The descriptor must read without diagnostics, apart from missing referenced files. It opens in an
 * editor tab like every karpfen file.
 */
public interface FileTemplates {

    /** The file name for [baseName]. It adds [FileTemplateKind.suffix] unless the name ends with it already. */
    public fun fileName(kind: FileTemplateKind, baseName: String): String

    /** The initial content of a new [kind] file named [baseName], created in [context]. [baseName] has no suffix. */
    public fun content(kind: FileTemplateKind, baseName: String, context: TemplateContext): String
}

/** The built-in templates. */
public fun FileTemplates(): FileTemplates = DefaultFileTemplates()

/**
 * The built-in templates. [fileName] holds the file name rule. The content per kind comes from
 * [KarpfenSourceTemplates] for KMeta, KModel and KStates and from [EnvironmentTemplate] for the descriptor. [fileName]
 * trims the name and does not add the kind's suffix to a `baseName` that already ends with it, ignoring case. "robot"
 * and "robot.kmeta" therefore create the same file.
 */
internal class DefaultFileTemplates : FileTemplates {

    override fun fileName(kind: FileTemplateKind, baseName: String): String {
        val name = baseName.trim()
        return if (name.endsWith(kind.suffix, ignoreCase = true)) name else name + kind.suffix
    }

    override fun content(kind: FileTemplateKind, baseName: String, context: TemplateContext): String {
        val name = fileName(kind, baseName).dropLast(kind.suffix.length)
        return when (kind) {
            FileTemplateKind.KMETA -> KarpfenSourceTemplates.kmeta(name)
            FileTemplateKind.KMODEL -> KarpfenSourceTemplates.kmodel(name, context)
            FileTemplateKind.KSTATES -> KarpfenSourceTemplates.kstates(name, context)
            FileTemplateKind.ENVIRONMENT -> EnvironmentTemplate.content(name, context)
        }
    }
}
