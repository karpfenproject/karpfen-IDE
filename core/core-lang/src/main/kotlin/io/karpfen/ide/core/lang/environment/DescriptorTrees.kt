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

import io.karpfen.ide.core.document.json.JsonDocument
import io.karpfen.ide.core.document.json.JsonParser
import io.karpfen.ide.core.text.TextSnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * The JSON tree of a descriptor text, for the environment providers. The parse outcome of a descriptor carries the
 * outline, spans and diagnostics but not the tree itself. So the providers read the text again with the [JsonParser]
 * of core-document. Descriptors are a few hundred characters long. The tree always belongs to the text of the request,
 * so the staleness rule of `LanguageDocument` never applies. The tree of the latest text is kept, because completion,
 * hover and bracket matching ask for the same text one after the other. The class is thread-safe. The kept pair of
 * text and tree is replaced atomically, and both parts are immutable.
 */
internal class DescriptorTrees {

    private val latest = AtomicReference<Pair<TextSnapshot, JsonDocument>?>(null)

    /** The tree of [text], parsed once per snapshot. */
    fun of(text: TextSnapshot): JsonDocument {
        latest.get()?.takeIf { it.first === text }?.let { return it.second }
        return JsonParser.parse(text.text).also { latest.set(text to it) }
    }
}
