package io.karpfen.ide.core.text

/**
 * Identity of the `core-text` module, kept from the phase-0 skeleton: the text of one document, with atomic edits,
 * immutable snapshots, line and column positions, undo and redo, and the mapping of positions between versions. Start
 * at [EditableText] and [TextSnapshot]; the module list of the README places the module among the others.
 */
public object CoreTextModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "core-text"
}
