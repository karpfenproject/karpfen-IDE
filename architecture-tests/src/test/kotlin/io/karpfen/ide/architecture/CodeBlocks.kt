package io.karpfen.ide.architecture

/** The kind of brace block a piece of Kotlin code stands in. */
internal enum class BlockKind {
    /** The top level of a file or script, outside every brace. */
    TOP_LEVEL,

    /** The body of a class, interface or object, which holds member declarations. */
    DECLARATIONS,

    /** The body of an enum class, which holds its entries and member declarations. */
    ENUM_BODY,

    /** Any other block: a function body, a lambda, an initializer, an accessor or a control-flow block. */
    CODE,
}

/**
 * Follows the brace blocks of Kotlin code while [KotlinComments] scans it, so a comment knows the kind of block it
 * stands in ([current]) and how deep it is nested in parentheses there ([parenDepth]). The scanner reports the code it
 * reads (words, parentheses, braces and semicolons); strings, character literals and comments never reach this class.
 *
 * Whether a `{` opens a declaration body is decided by the last declaration keyword written at the same parenthesis
 * depth since the previous statement boundary: `class`, `interface` or `object` open a body of declarations (`enum
 * class` an enum body), any other keyword (`fun`, `val`, `init`, `get`, `if`, ...) or none opens a block of code. A
 * `constructor` right after a class keyword (`class A private constructor(...)`) keeps the class, and `Foo::class` is
 * no keyword. The boundaries are `;`, `{` and the `}` of a block that was not opened inside parentheses; a lambda
 * argument, whose block was, leaves the header around it intact. The rule is lexical and can be fooled by unusual
 * code, such as a local class without a body followed by a lambda statement, which then counts as a declaration body.
 */
internal class CodeBlocks {

    /**
     * One open brace block.
     *
     * @property kind the kind of the block.
     * @property outerHeaders the header levels of the enclosing block when this one opened, restored when it closes.
     */
    private class Frame(val kind: BlockKind, val outerHeaders: ArrayDeque<HeaderLevel>)

    /**
     * The header read so far at one parenthesis depth of the current block.
     *
     * @property keyword the last declaration keyword at this depth since the previous boundary, `enum class` for an enum.
     */
    private class HeaderLevel(var keyword: String? = null)

    private val frames = ArrayDeque<Frame>()

    /** The header levels of the current block, one per open parenthesis plus the block's own level. */
    private var headers = ArrayDeque(listOf(HeaderLevel()))

    /** The previous word read, to recognize `enum class`. */
    private var previousWord: String? = null

    /** The kind of the block the scanner is in. */
    val current: BlockKind get() = frames.lastOrNull()?.kind ?: BlockKind.TOP_LEVEL

    /** The number of parentheses open in the current block. */
    val parenDepth: Int get() = headers.size - 1

    /** Reads the identifier or keyword [word]; [isMemberReference] marks a word written right after `::`. */
    fun word(word: String, isMemberReference: Boolean) {
        val level = headers.last()
        when {
            isMemberReference || word !in KEYWORDS -> Unit
            word == "class" && previousWord == "enum" -> level.keyword = ENUM_CLASS
            word == "constructor" && level.keyword in CLASS_KEYWORDS -> Unit
            else -> level.keyword = word
        }
        previousWord = word
    }

    /** Reads `(`. */
    fun openParen() = headers.addLast(HeaderLevel())

    /** Reads `)`; an unbalanced one is ignored. */
    fun closeParen() {
        if (headers.size > 1) headers.removeLast()
    }

    /** Reads `;`, which ends a statement at the block's own level. */
    fun semicolon() {
        if (headers.size == 1) headers.last().keyword = null
    }

    /** Reads `{`, opening a block whose kind the header before it decides. */
    fun openBrace() {
        val kind = when (headers.last().keyword) {
            ENUM_CLASS -> BlockKind.ENUM_BODY
            in CLASS_KEYWORDS -> BlockKind.DECLARATIONS
            else -> BlockKind.CODE
        }
        frames.addLast(Frame(kind, headers))
        headers = ArrayDeque(listOf(HeaderLevel()))
    }

    /** Reads `}`, closing the current block; an unbalanced one is ignored. */
    fun closeBrace() {
        val frame = frames.removeLastOrNull() ?: return
        headers = frame.outerHeaders
        if (headers.size == 1) headers.last().keyword = null
    }

    private companion object {
        /** The keyword of an enum class header. */
        const val ENUM_CLASS = "enum class"

        /** The keywords whose block holds member declarations. */
        val CLASS_KEYWORDS = setOf("class", "interface", "object", ENUM_CLASS)

        /** The keywords that decide the kind of the next block. */
        val KEYWORDS = setOf(
            "class", "interface", "object", "fun", "val", "var", "init", "constructor", "get", "set", "typealias",
            "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "return", "throw",
        )
    }
}
