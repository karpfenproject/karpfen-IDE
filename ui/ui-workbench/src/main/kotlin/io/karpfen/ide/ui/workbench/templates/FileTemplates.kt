package io.karpfen.ide.ui.workbench.templates

import io.karpfen.ide.core.workspace.context.ModelingContext
import java.nio.file.Path

/**
 * Kinds of files the project tree can create (spec §8, "New KMeta/KModel/KStates/Environment file").
 *
 * @property suffix the file-name suffix including its dot (`.kmeta`, `.kenv.json`); unlike `KarpfenLanguage.extension`
 *   (`kmeta`, without the dot) it may span several dots.
 */
public enum class FileTemplateKind(public val suffix: String, public val displayName: String) {
    /** A metamodel with one example type. */
    KMETA(".kmeta", "KMeta File"),

    /** A model with one root object. */
    KMODEL(".kmodel", "KModel File"),

    /** A state machine with one initial state. */
    KSTATES(".kstates", "KStates File"),

    /** An environment descriptor (`*.kenv.json`). */
    ENVIRONMENT(".kenv.json", "Environment File"),
}

/**
 * What a template knows about where the new file goes; built by `WorkbenchState.createFile` from `Workspace.contexts`
 * and the IDE-owned syntax indexes (`AnalyzedFile.parse.index`) of `Workspace.analysis` — never from the converted
 * dsl-tools models, so it is also known while a metamodel does not convert (decision 45 of the work-package plan).
 *
 * @property directory the target directory.
 * @property context the modeling context the directory forms or is covered by before the file is created (an implicit
 *   context of the directory, or the explicit context of its descriptor), or null.
 * @property typeNames the TYPE declarations of that context's domain metamodel, in declaration order (empty if unknown).
 * @property objects the OBJECT declarations of that context's first model (nested ones included), in document order:
 *   key and type name (`SymbolDeclaration.typeName`); empty if unknown.
 * @property stateMachineTypes the `ATTACHED TO` type (`SymbolDeclaration.typeName` of the STATEMACHINE declaration) of
 *   each state machine of the context, by path; a machine whose type is unknown is absent.
 */
public data class TemplateContext(
    public val directory: Path,
    public val context: ModelingContext? = null,
    public val typeNames: List<String> = emptyList(),
    public val objects: List<TemplateObject> = emptyList(),
    public val stateMachineTypes: Map<Path, String> = emptyMap(),
)

/** An object a descriptor can attach a state machine to: its [key] and its [typeName] (null if the text omits it). */
public data class TemplateObject(public val key: String, public val typeName: String?)

/**
 * Content of new files (spec §8). Generated karpfen sources must tokenize and parse without diagnostics and, where the
 * [TemplateContext] allows, convert in their context:
 * - KMeta: one example type named after the file;
 * - KModel: one root object of the context's first domain type (`TemplateContext.typeNames`) when known;
 * - KStates: `ATTACHED TO` the context's first domain type when known, one initial state;
 * - Environment: a descriptor naming the files of the directory's current context (metamodel, events, its first model,
 *   its state machines), paths relative to the directory, so creating it keeps the directory's files in one context
 *   instead of turning them into loose files. Each state machine is attached to the key of the first object whose type
 *   equals the machine's `ATTACHED TO` type (`stateMachineTypes`), else to the first object's key, else to the
 *   placeholder `"object"` (e.g. single_robot_example: `cleaning_robot.kstates` → `"turtle"`, the Robot, not the root
 *   Room). Without a context, files named after the descriptor (reported as `descriptor-missing-file` until created).
 *   It must read without diagnostics except missing referenced files.
 */
public interface FileTemplates {

    /** The file name for [baseName] (adds [FileTemplateKind.suffix] unless already present). */
    public fun fileName(kind: FileTemplateKind, baseName: String): String

    /** The initial content of a new [kind] file named [baseName] (without suffix) created in [context]. */
    public fun content(kind: FileTemplateKind, baseName: String, context: TemplateContext): String
}

/** The built-in templates. */
public fun FileTemplates(): FileTemplates = DefaultFileTemplates()

internal class DefaultFileTemplates : FileTemplates {
    override fun fileName(kind: FileTemplateKind, baseName: String): String = TODO("WP-WORKBENCH: template file name")

    override fun content(kind: FileTemplateKind, baseName: String, context: TemplateContext): String =
        TODO("WP-WORKBENCH: template content in ${context.directory}")
}
