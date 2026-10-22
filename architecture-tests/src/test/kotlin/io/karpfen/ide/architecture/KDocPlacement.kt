package io.karpfen.ide.architecture

/**
 * A KDoc comment that documents nothing a reader of the API documentation sees.
 *
 * @property reason why its place is wrong, for failure messages.
 */
internal data class MisplacedKDoc(val comment: SourceComment, val reason: String)

/**
 * Checks where KDoc stands. KDoc documents a declaration, so it is only allowed on its own line right before one, after
 * which come only annotations, modifiers and context parameters (`context(...)`):
 *
 * - in a Kotlin file, a top-level declaration or a member of a class, interface, object or enum entry body (a class,
 *   interface, object, function, property, type alias or secondary constructor), an enum entry in an enum body, or a
 *   property of a primary constructor;
 * - in a script (a Gradle script or another `.kts` file), additionally any top-level statement or block, such as
 *   `dependencies { }`, because a script has no other place to document them.
 *
 * Everything else is a comment in disguise and is rejected: KDoc after code on the same line, KDoc inside a function
 * body or lambda (a statement or a local declaration, which no API documentation shows), KDoc between statements of a
 * script block, and KDoc that stands before another comment or at the end of the text.
 */
internal object KDocPlacement {

    /** The misplaced KDoc comments of [source]; [isScript] is true for a `.kts` file. */
    fun check(source: String, isScript: Boolean): List<MisplacedKDoc> =
        KotlinComments.scanPlaced(source)
            .filter { it.comment.kind == CommentKind.KDOC }
            .mapNotNull { placed -> reasonAgainst(source, placed, isScript)?.let { MisplacedKDoc(placed.comment, it) } }

    /** Why the KDoc [placed] in [source] is misplaced, or null if it documents a declaration. */
    private fun reasonAgainst(source: String, placed: PlacedComment, isScript: Boolean): String? {
        val next = nextCode(source, placed.offset + placed.comment.text.length)
        return when {
            source.substring(lineStart(source, placed.offset), placed.offset).isNotBlank() -> "follows code on its line"
            placed.block == BlockKind.CODE -> "stands inside a function body or lambda"
            next == null || source.startsWith("/", next) -> "documents nothing: another comment or the end follows"
            isScript && placed.block == BlockKind.TOP_LEVEL && placed.parenDepth == 0 -> null
            documentsDeclaration(source, next, placed.block) -> null
            else -> "is not followed by a declaration"
        }
    }

    /**
     * True if a declaration, after its annotations, modifiers and context parameters, starts at [start] of [source] in a
     * block of [block].
     */
    private fun documentsDeclaration(source: String, start: Int, block: BlockKind): Boolean {
        var offset = start
        while (true) {
            offset = skipBlanks(source, offset)
            if (source.startsWith("@", offset)) {
                offset = endOfAnnotation(source, offset)
                continue
            }
            val word = wordAt(source, offset)
            val afterWord = skipBlanks(source, offset + word.length)
            when {
                word == CONTEXT && source.startsWith("(", afterWord) -> offset = endOfArguments(source, afterWord)
                word in MODIFIERS -> offset += word.length
                word in DECLARATION_KEYWORDS -> return true
                else -> return block == BlockKind.ENUM_BODY && word.isNotEmpty()
            }
        }
    }

    /** The offset of the first character after [from] that is no whitespace, or null at the end of [source]. */
    private fun nextCode(source: String, from: Int): Int? = skipBlanks(source, from).takeIf { it < source.length }

    private fun skipBlanks(source: String, from: Int): Int {
        var offset = from
        while (offset < source.length && source[offset].isWhitespace()) offset++
        return offset
    }

    private fun lineStart(source: String, offset: Int): Int = source.lastIndexOf('\n', offset - 1) + 1

    /** The identifier at [offset], or the empty string if none starts there. */
    private fun wordAt(source: String, offset: Int): String {
        var end = offset
        while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end++
        return source.substring(offset, end)
    }

    /**
     * The offset after the annotation at [start]: `@`, a name that may hold `.` and a use-site target with `:`, and
     * an optional argument list.
     */
    private fun endOfAnnotation(source: String, start: Int): Int {
        var offset = start + 1
        while (offset < source.length && (source[offset].isLetterOrDigit() || source[offset] in "_.:")) offset++
        return if (source.startsWith("(", offset)) endOfArguments(source, offset) else offset
    }

    /** The offset after the list in parentheses that opens at [start], whose strings may hold parentheses of their own. */
    private fun endOfArguments(source: String, start: Int): Int {
        var offset = start
        var depth = 0
        while (offset < source.length) {
            when (source[offset]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return offset + 1
                '"' -> offset = endOfString(source, offset)
            }
            offset++
        }
        return offset
    }

    /** The offset of the quote that closes the regular string opening at [start]. */
    private fun endOfString(source: String, start: Int): Int {
        var offset = start + 1
        while (offset < source.length && source[offset] != '"') offset += if (source[offset] == '\\') 2 else 1
        return offset
    }

    /** The modifiers that may stand between KDoc and the keyword of its declaration. */
    private val MODIFIERS = setOf(
        "public", "private", "internal", "protected", "open", "final", "abstract", "sealed", "data", "enum", "annotation",
        "inner", "value", "inline", "noinline", "crossinline", "const", "lateinit", "override", "suspend", "operator",
        "infix", "tailrec", "external", "companion", "expect", "actual", "vararg",
    )

    /** The soft keyword of the context parameters a declaration may have before its modifiers. */
    private const val CONTEXT = "context"

    /** The keywords that start a declaration KDoc may document. */
    private val DECLARATION_KEYWORDS = setOf("class", "interface", "object", "fun", "val", "var", "typealias", "constructor")
}
