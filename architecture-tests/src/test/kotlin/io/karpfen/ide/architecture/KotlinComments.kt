package io.karpfen.ide.architecture

/**
 * A comment found in Kotlin source text.
 *
 * @property kind whether it is a line comment, a plain block comment or KDoc.
 * @property line the 1-based line on which the comment starts.
 * @property text the whole comment, delimiters included.
 */
internal data class SourceComment(val kind: CommentKind, val line: Int, val text: String) {

    /** The 1-based line of the character at [offset] in [text], counted from the line on which the comment starts. */
    fun lineAt(offset: Int): Int = line + text.substring(0, offset).count { it == '\n' }

    /** The first line of [text], shortened to [maxLength] characters, for failure messages. */
    fun excerpt(maxLength: Int = 80): String {
        val firstLine = text.lineSequence().first().trim()
        return if (firstLine.length <= maxLength) firstLine else firstLine.take(maxLength - 1) + "…"
    }
}

/**
 * Finds the comments in Kotlin source text, in `.kt` files and in Gradle's `.kts` scripts alike. The comments are the
 * comment spans of [KotlinLexer], whose documentation lists the lexical rules that tell a comment from text that only
 * looks like one, such as a URL in a string.
 */
internal object KotlinComments {

    /** The comments of [source] in the order in which they appear. */
    fun scan(source: String): List<SourceComment> {
        val lineBreaks = source.indices.filter { source[it] == '\n' }.toIntArray()
        return KotlinLexer.spans(source).mapNotNull { span ->
            val kind = span.kind as? CommentKind ?: return@mapNotNull null
            SourceComment(kind, lineOf(span.start, lineBreaks), source.substring(span.start, span.end))
        }
    }

    /** The 1-based line of the character at [offset]: one more than the number of [lineBreaks] before it. */
    private fun lineOf(offset: Int, lineBreaks: IntArray): Int {
        val index = lineBreaks.binarySearch(offset)
        return (if (index >= 0) index else -index - 1) + 1
    }
}
