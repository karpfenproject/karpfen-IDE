package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [KDocPlacement] and the block tracking of [CodeBlocks] behind it: KDoc on every kind of declaration passes,
 * KDoc in the place of a trailing comment or a comment between statements does not, in Kotlin files and scripts.
 */
class KDocPlacementTest {

    /** The lines and reasons of the misplaced KDoc in [source]. */
    private fun misplaced(source: String, isScript: Boolean = false): List<Pair<Int, String>> =
        KDocPlacement.check(source, isScript).map { it.comment.line to it.reason }

    @Test
    fun `KDoc on top-level declarations, members, enum entries and constructor properties is in place`() {
        val source = """
            /** A class. */
            @Suppress("x (y)")
            public data class A(
                /** A property. */
                private val a: Int,
            ) {
                /** A member. */
                override fun toString(): String = "a"

                /** An object. */
                companion object {
                    /** A constant. */
                    const val B = 1
                }
            }

            /** An enum. */
            enum class E(val label: String) {
                /** An entry. */
                FIRST("1"),

                /** Another. */
                @Deprecated("no")
                SECOND("2"),
                ;

                /** A member after the entries. */
                val upper: String get() = label.uppercase()
            }

            /** A class with a private constructor. */
            internal class C private constructor(val c: Int) {
                /** A secondary constructor. */
                constructor() : this(0)

                /** A member of an anonymous object. */
                val listener = object : Runnable {
                    /** Runs. */
                    override fun run() = Unit
                }
            }

            /** A function. */
            fun f(x: KClass<*> = String::class) = listOf(1).map { it }

            /** An alias. */
            typealias T = Int
        """.trimIndent()

        assertEquals(emptyList(), misplaced(source))
    }

    @Test
    fun `KDoc on the members of an enum entry's body and on declarations with context parameters is in place`() {
        val source = """
            /** An enum whose entries behave differently. */
            enum class E(val label: String) {
                /** An entry with a body. */
                A("a") {
                    /** Its override. */
                    override fun f() = 1
                },

                /** Another one. */
                B("b") {
                    /** Its override, with a lambda inside. */
                    override fun f() = listOf(1).map { it + 1 }.first()
                };

                /** Implemented by every entry. */
                abstract fun f(): Int
            }

            /** With a context parameter. */
            context(foo: Foo)
            fun bar() = 1

            /** With an annotation and context parameters. */
            @Suppress("x")
            context(Foo, bar: Bar)
            internal fun baz() = 2
        """.trimIndent()

        assertEquals(emptyList(), misplaced(source))
    }

    @Test
    fun `KDoc inside a function of an enum entry's body still stands inside a function body`() {
        val source = "enum class E {\n    A {\n        override fun f() {\n            /** Inside. */\n            g()\n        }\n    }\n}"

        assertEquals(listOf(4 to "stands inside a function body or lambda"), misplaced(source))
    }

    @Test
    fun `KDoc after code on the same line is a trailing comment`() {
        assertEquals(listOf(1 to "follows code on its line"), misplaced("val a = 1 /** note */\nval b = 2"))
    }

    @Test
    fun `KDoc between statements or on a local declaration stands inside a function body`() {
        val source = """
            fun f() {
                val a = 1
                /** Between statements. */
                println(a)
                listOf(a).forEach {
                    /** A local function in a lambda. */
                    fun g() = it
                }
            }
        """.trimIndent()

        assertEquals(
            listOf(3 to "stands inside a function body or lambda", 6 to "stands inside a function body or lambda"),
            misplaced(source),
        )
    }

    @Test
    fun `KDoc that documents nothing is misplaced`() {
        val nothing = "documents nothing: another comment or the end follows"
        assertEquals(listOf(1 to nothing, 4 to nothing), misplaced("/** One. */\n/** Two. */\nval a = 1\n/** Last. */"))
        assertEquals(listOf(1 to "is not followed by a declaration"), misplaced("/** A package. */\npackage a.b\n"))
    }

    @Test
    fun `in a script KDoc documents top-level statements and blocks, but not the statements inside them`() {
        val script = """
            /** The plugins. */
            plugins {
                id("a")
            }

            /** The dependencies. */
            dependencies {
                api(project(":a"))
                /** Between two dependencies. */
                api(project(":b")) {
                    /** Inside a lambda. */
                    exclude(group = "x")
                }
            }
        """.trimIndent()

        assertEquals(
            listOf(9 to "stands inside a function body or lambda", 11 to "stands inside a function body or lambda"),
            misplaced(script, isScript = true),
        )
    }

    @Test
    fun `blocks are told apart by the keyword before their brace`() {
        val blocks = KotlinComments.scanPlaced(
            "class A(val f: () -> Unit = { /**/ }) { /**/ init { /**/ } }\nenum class E { /**/ A({ /**/ }) { /**/ } }\n" +
                "val x = run { /**/ }",
        ).map { it.block }

        assertEquals(
            listOf(
                BlockKind.CODE, BlockKind.DECLARATIONS, BlockKind.CODE, BlockKind.ENUM_BODY, BlockKind.CODE, BlockKind.DECLARATIONS,
                BlockKind.CODE,
            ),
            blocks,
        )
    }
}
