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
package io.karpfen.ide.core.lang.environment

/**
 * Text that descriptor completion writes, with the place the caret goes to after writing it.
 *
 * @property caret the offset of the caret in [text]. It is the first place to fill, or the end of the text.
 * @throws IllegalArgumentException if [caret] lies outside [text].
 */
internal data class Template(val text: String, val caret: Int = text.length) {
    init {
        require(caret in 0..text.length) { "caret $caret must lie within the text (length ${text.length})" }
    }
}
