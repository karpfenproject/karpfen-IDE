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
 * and `Workspace.analysis`.
 *
 * @property directory the target directory.
 * @property context the modeling context the directory forms or is covered by before the file is created (an implicit
 *   context of the directory, or the explicit context of its descriptor), or null.
 * @property typeNames the domain types of that context's converted metamodel, in declaration order (empty if unknown).
 * @property objectKeys the keys of the objects of that context's first model, in declaration order (empty if unknown).
 */
public data class TemplateContext(
    public val directory: Path,
    public val context: ModelingContext? = null,
    public val typeNames: List<String> = emptyList(),
    public val objectKeys: List<String> = emptyList(),
)

/**
 * Content of new files (spec §8). Generated karpfen sources must tokenize and parse without diagnostics and, where the
 * [TemplateContext] allows, convert in their context:
 * - KMeta: one example type named after the file;
 * - KModel: one root object of the context's first domain type (`TemplateContext.typeNames`) when known;
 * - KStates: `ATTACHED TO` the context's first domain type when known, one initial state;
 * - Environment: a descriptor naming the files of the directory's current context (metamodel, events, its first model,
 *   its state machines attached to the first object key), paths relative to the directory, so creating it keeps the
 *   directory's files in one context instead of turning them into loose files; without a context, files named after
 *   the descriptor (reported as `descriptor-missing-file` until created). It must read without diagnostics except
 *   missing referenced files.
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
