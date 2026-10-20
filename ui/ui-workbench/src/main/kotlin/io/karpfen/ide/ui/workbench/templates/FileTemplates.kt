package io.karpfen.ide.ui.workbench.templates

/** Kinds of files the project tree can create (spec §8, "New KMeta/KModel/KStates/Environment file"). */
public enum class FileTemplateKind(public val extension: String, public val displayName: String) {
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
 * Content of new files. Generated karpfen sources must tokenize and parse without diagnostics; the environment
 * template must read without diagnostics (except missing referenced files).
 */
public interface FileTemplates {

    /** The file name for [baseName] (adds the extension unless already present). */
    public fun fileName(kind: FileTemplateKind, baseName: String): String

    /** The initial content of a new [kind] file named [baseName] (without extension). */
    public fun content(kind: FileTemplateKind, baseName: String): String
}

/** The built-in templates. */
public fun FileTemplates(): FileTemplates = DefaultFileTemplates()

internal class DefaultFileTemplates : FileTemplates {
    override fun fileName(kind: FileTemplateKind, baseName: String): String = TODO("WP-WORKBENCH: template file name")

    override fun content(kind: FileTemplateKind, baseName: String): String = TODO("WP-WORKBENCH: template content")
}
