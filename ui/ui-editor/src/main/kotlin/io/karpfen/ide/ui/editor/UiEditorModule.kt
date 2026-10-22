package io.karpfen.ide.ui.editor

/**
 * Identity of the `ui-editor` module, which holds the custom code editor: its Compose-free model, its state holder,
 * the view, the popups and the editor themes.
 */
public object UiEditorModule {
    /** The module's name as the build and the module dependency checks know it. */
    public const val ID: String = "ui-editor"
}
