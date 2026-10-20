package io.karpfen.ide.core.text

/**
 * Why a change was made. The origin drives undo coalescing (spec §2) and lets observers tell user edits from
 * reloads.
 *
 * Coalescing (implemented by the [UndoManager]): only [Typing] changes coalesce; [Paste], [Cut], [Command] and
 * [External] never do; [Undo]/[Redo] are produced by the undo manager itself; [Programmatic] never coalesces.
 */
public sealed interface EditOrigin {

    /** Keyboard text input, including Backspace/Delete of single characters and auto-inserted closers. */
    public data object Typing : EditOrigin

    /** Text inserted from the clipboard. */
    public data object Paste : EditOrigin

    /** Text removed to the clipboard. */
    public data object Cut : EditOrigin

    /** A change produced by [UndoManager.undo]. */
    public data object Undo : EditOrigin

    /** A change produced by [UndoManager.redo]. */
    public data object Redo : EditOrigin

    /** A change from outside the editor, e.g. reloading a file that changed on disk. */
    public data object External : EditOrigin

    /** An editor command such as "duplicate line", "toggle comment", "replace all" or "accept completion". */
    public data class Command(public val name: String) : EditOrigin

    /** A change made by code without a user gesture (tests, refactorings, generated edits). */
    public data class Programmatic(public val name: String) : EditOrigin
}
