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
 * Identity of the `core-text` module. The module holds the text of one document, with atomic edits, immutable
 * snapshots, line and column positions, undo and redo, and the mapping of positions between versions. Start reading at
 * [EditableText] and [TextSnapshot].
 */
public object CoreTextModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "core-text"
}
