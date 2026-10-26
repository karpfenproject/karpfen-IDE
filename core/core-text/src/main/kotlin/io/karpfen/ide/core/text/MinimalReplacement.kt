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
 * The single edit that turns [oldText] into [newText] keeping their common prefix and suffix, or null if the texts
 * are equal. The prefix is taken first, so for ambiguous cases (`"aa"` → `"aaa"`) the edit is an insertion at the end.
 */
internal fun minimalReplacement(oldText: String, newText: String): TextEdit? {
    if (oldText == newText) return null
    val maxCommon = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < maxCommon && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < maxCommon - prefix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) {
        suffix++
    }
    return TextEdit(TextRange(prefix, oldText.length - suffix), newText.substring(prefix, newText.length - suffix))
}
