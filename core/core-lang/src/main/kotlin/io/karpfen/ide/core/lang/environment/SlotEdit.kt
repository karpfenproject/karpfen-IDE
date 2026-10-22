package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.text.TextRange

/**
 * How completion writes a proposal at a [DescriptorSlot], so that the descriptor stays valid JSON around it: the
 * [range] the proposal replaces, how it meets the quotes of the string at the caret, and the separators the text lacks
 * around the member or item it writes. [SlotEdits] works it out; the completion provider fits every proposal into it
 * with [fit].
 *
 * @property range what the proposal replaces.
 * @property quotes how the proposal meets the quotes of the string at the caret.
 * @property leading what goes in front of the proposal: `, ` for a member or item after the last one of its object or
 *   list, which has no comma behind it yet; empty elsewhere.
 * @property trailing what goes behind the proposal: `,` for a member or item that another one follows without a comma
 *   in between, `, ` when that one starts right behind [range]; empty elsewhere.
 */
internal data class SlotEdit(
    val range: TextRange,
    val quotes: Quotes,
    val leading: String = "",
    val trailing: String = "",
) {
    /** True if the caret is outside any string, where proposals bring their own quotes and may be any JSON value. */
    val isOutsideString: Boolean get() = quotes == Quotes.OWN

    /**
     * The text to write for [proposal], written as JSON: without the quotes the text keeps ([Quotes]), and between
     * [leading] and [trailing]. The caret goes to the proposal's own caret place, which stays in front of [trailing].
     *
     * @throws IllegalArgumentException if the caret is in a string and [proposal] is no JSON text that starts with a
     *   string (a string, or a member whose key the string is), or, where the string's quotes stay, is no string.
     */
    fun fit(proposal: Template): Template {
        val text = proposal.text
        val start = if (quotes == Quotes.OWN) 0 else 1
        val end = if (quotes == Quotes.KEPT) text.length - 1 else text.length
        require(start == 0 || text.startsWith('"')) { "a proposal in a string starts with its quote: $text" }
        require(quotes != Quotes.KEPT || (text.length >= 2 && text.endsWith('"'))) { "a proposal within a string is one: $text" }
        val core = text.substring(start, end)
        return Template(leading + core + trailing, leading.length + (proposal.caret - start).coerceIn(0, core.length))
    }
}

/** How a proposal meets the quotes of the string at the caret. */
internal enum class Quotes {
    /** The caret is outside any string: the proposal brings its own quotes. */
    OWN,

    /** The caret is in a string whose quotes stay: the proposal replaces the content between them. */
    KEPT,

    /**
     * The caret is in a string whose opening quote stays and which the proposal ends: the proposal is written after
     * that quote and brings the closing one, because the string has none, or because more follows the string (the `:`
     * of a key that becomes a member, the `,` in front of the next item) and the proposal takes its closing quote in.
     */
    OPENED,
}
