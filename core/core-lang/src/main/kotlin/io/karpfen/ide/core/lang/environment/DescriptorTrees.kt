package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.json.JsonDocument
import io.karpfen.ide.core.document.json.JsonParser
import io.karpfen.ide.core.text.TextSnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * The JSON tree of a descriptor text, for the environment providers. A descriptor's parse outcome carries outline,
 * spans and diagnostics but not the tree itself, so the providers read the text again with core-document's
 * [JsonParser]; descriptors are a few hundred characters, and the tree always belongs to the request's own text, so the
 * staleness rule of `LanguageDocument` never applies. The tree of the latest text is kept, because completion, hover
 * and bracket matching ask for the same text one after the other. Thread-safe: the kept pair is replaced atomically and
 * both parts are immutable.
 */
internal class DescriptorTrees {

    private val latest = AtomicReference<Pair<TextSnapshot, JsonDocument>?>(null)

    /** The tree of [text], parsed once per snapshot. */
    fun of(text: TextSnapshot): JsonDocument {
        latest.get()?.takeIf { it.first === text }?.let { return it.second }
        return JsonParser.parse(text.text).also { latest.set(text to it) }
    }
}
