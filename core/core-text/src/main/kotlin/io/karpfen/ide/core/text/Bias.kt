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
 * How offsets are mapped across an edit when they sit exactly at an insertion point or inside a replaced range.
 *
 * - At an insertion offset: [LEFT] stays before the inserted text, [RIGHT] moves behind it.
 * - Inside a replaced or deleted range: [LEFT] maps to the start of the replacement, [RIGHT] to its end.
 * - Offsets before or after every edit are shifted unambiguously, independent of the bias.
 */
public enum class Bias {
    /** Stick to the text before the edit point. */
    LEFT,

    /** Stick to the text after the edit point (default: carets move along with typed text). */
    RIGHT,
}
