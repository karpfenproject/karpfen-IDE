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
 * Follows the brace blocks of Kotlin code while [KotlinComments] scans it. A comment can then tell the kind of block it
 * stands in ([current]) and how deep it is nested in parentheses there ([parenDepth]). The scanner hands over the code
 * it reads, which is words, parentheses, braces and semicolons. Strings, character literals and comments never reach
 * this class.
 *
 * The last declaration keyword decides whether a `{` opens a declaration body. Only a keyword written at the same
 * parenthesis depth since the previous statement boundary counts. After `class`, `interface` or `object` the brace
 * opens a body of declarations, and after `enum class` it opens an enum body. After any other keyword, such as `fun`,
 * `val`, `init`, `get` or `if`, or after no keyword at all, it opens a block of code.
 *
 * There is one exception. A `{` without a keyword, directly in an enum body and outside parentheses, opens the body of
 * an enum entry, as in `A { override fun f() = 1 }`. Such a body is an anonymous class, so its members are
 * declarations. A `constructor` right after a class keyword, as in `class A private constructor(...)`, keeps the class
 * keyword in force. In `Foo::class` the word `class` is no keyword.
 *
 * The statement boundaries are `;`, `{` and the `}` of a block that was not opened inside parentheses. The block of a
 * lambda argument was opened inside parentheses, so it leaves the header around it intact.
 *
 * The rule is lexical, and unusual code can fool it. A local class without a body, followed by a lambda statement,
 * makes the lambda count as a declaration body.
 */
internal class CodeBlocks {

    /**
     * One open brace block.
     *
     * @property kind what the block holds, which decides whether KDoc may stand in it.
     * @property outerHeaders the header levels of the enclosing block when this one opened, restored when it closes.
     */
    private class Frame(val kind: BlockKind, val outerHeaders: ArrayDeque<HeaderLevel>)

    /**
     * The header read so far at one parenthesis depth of the current block.
     *
     * @property keyword the last declaration keyword at this depth since the previous boundary, `enum class` for an enum.
     */
    private class HeaderLevel(var keyword: String? = null)

    /** The open brace blocks, the innermost one last. The top level of the text has no frame. */
    private val frames = ArrayDeque<Frame>()

    /** The header levels of the current block, one per open parenthesis plus the block's own level. */
    private var headers = ArrayDeque(listOf(HeaderLevel()))

    /** The previous word read, to recognize `enum class`. */
    private var previousWord: String? = null

    /** The kind of the block the scanner is in. */
    val current: BlockKind get() = frames.lastOrNull()?.kind ?: BlockKind.TOP_LEVEL

    /** The number of parentheses open in the current block. */
    val parenDepth: Int get() = headers.size - 1

    /**
     * Reads the identifier or keyword [word]. [isMemberReference] is true for a word written right after `::`, which is
     * never a keyword.
     */
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

    /** Reads `)`. An unbalanced one is ignored. */
    fun closeParen() {
        if (headers.size > 1) headers.removeLast()
    }

    /** Reads `;`, which ends a statement at the block's own level. */
    fun semicolon() {
        if (headers.size == 1) headers.last().keyword = null
    }

    /** Reads `{`, opening a block whose kind the header before it decides. */
    fun openBrace() {
        val keyword = headers.last().keyword
        val kind = when {
            keyword == ENUM_CLASS -> BlockKind.ENUM_BODY
            keyword in CLASS_KEYWORDS || opensEnumEntryBody(keyword) -> BlockKind.DECLARATIONS
            else -> BlockKind.CODE
        }
        frames.addLast(Frame(kind, headers))
        headers = ArrayDeque(listOf(HeaderLevel()))
    }

    /** True if a `{` after [keyword] opens the body of an enum entry: no keyword, directly in an enum body. */
    private fun opensEnumEntryBody(keyword: String?): Boolean =
        keyword == null && current == BlockKind.ENUM_BODY && parenDepth == 0

    /**
     * Reads `}` and closes the current block. The header levels of the enclosing block come back. If the closed block
     * was not opened inside parentheses, the `}` ends a statement of the enclosing block, so the keyword read there is
     * forgotten. An unbalanced `}` is ignored.
     */
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
