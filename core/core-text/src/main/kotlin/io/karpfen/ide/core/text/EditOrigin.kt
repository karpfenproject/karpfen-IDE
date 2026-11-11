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
package io.karpfen.ide.core.text

/**
 * Why a change was made. The origin drives undo coalescing and lets observers tell user edits from reloads.
 *
 * The [UndoManager] implements the coalescing. Only [Typing] changes coalesce. [Paste], [Cut], [Command], [External]
 * and [Programmatic] changes never do. [Undo] and [Redo] changes are produced by the undo manager itself.
 */
public sealed interface EditOrigin {

    /** Keyboard text input, including Backspace or Delete of single characters and auto-inserted closers. */
    public data object Typing : EditOrigin

    /** Text inserted from the clipboard. */
    public data object Paste : EditOrigin

    /** Text removed to the clipboard. */
    public data object Cut : EditOrigin

    /** A change produced by [UndoManager.undo]. */
    public data object Undo : EditOrigin

    /** A change produced by [UndoManager.redo]. */
    public data object Redo : EditOrigin

    /** A change from outside the editor, for example the reload of a file that changed on disk. */
    public data object External : EditOrigin

    /** An editor command such as "duplicate line", "toggle comment", "replace all" or "accept completion". */
    public data class Command(public val name: String) : EditOrigin

    /** A change made by code without a user gesture, such as a test, a refactoring or a generated edit. */
    public data class Programmatic(public val name: String) : EditOrigin
}
