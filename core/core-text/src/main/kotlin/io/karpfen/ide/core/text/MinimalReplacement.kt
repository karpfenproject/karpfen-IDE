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
