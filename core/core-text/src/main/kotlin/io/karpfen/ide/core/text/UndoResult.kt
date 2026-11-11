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
 * Result of [UndoManager.undo] or [UndoManager.redo].
 *
 * @property change the change that undo or redo applied to the text, with origin [EditOrigin.Undo] or
 *   [EditOrigin.Redo].
 * @property carets the carets to restore, in the coordinates of `change.after`. For undo these are the carets recorded
 *   *before* the undone step, which is the `caretsBefore` of its first change. For redo these are the carets recorded
 *   *after* the redone step, which is the `caretsAfter` of its last change. Null if that change recorded none.
 */
public data class UndoResult(public val change: TextChange, public val carets: List<CaretMemento>?)
