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
 * The text as the [DefaultUndoManager] sees it. Undo and redo apply their edits through this port, which bypasses the
 * recording of history. They publish the change once the history is consistent again.
 */
internal interface EditTarget {

    /**
     * Applies [edits], given in the coordinates of the current version, as one new version with [origin]. It records no
     * undo history and queues the change for publication. Returns null if the edits change nothing.
     */
    fun commit(edits: List<TextEdit>, origin: EditOrigin): TextChange?

    /** Publishes every committed but unpublished change to the observers of the snapshot and change flows. */
    fun publishPending()
}
