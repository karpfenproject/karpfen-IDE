package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [Lambdas], which tells the lambdas of a function from its other blocks and finds the code that gives their
 * parameters their values: the receiver and the arguments of the call that takes a trailing lambda.
 */
class LambdasTest {

    /** The lambdas of [code], each as its parameters and the texts of its inputs. */
    private fun lambdasIn(code: String): List<Pair<List<String>, List<String>>> =
        Lambdas.findIn(code).map { lambda -> lambda.parameters to lambda.inputs.map { code.textOf(it).trim() } }

    @Test
    fun `a trailing lambda takes its inputs from the receiver and the argument list of its call`() {
        assertEquals(listOf(listOf("item") to listOf("items")), lambdasIn("items.forEach { item -> check(item) }"))
        assertEquals(
            listOf(listOf("it") to emptyList(), listOf("it") to listOf("measureTime { work() }")),
            lambdasIn("measureTime { work() }\n    .let { report(it) }"),
        )
        assertEquals(listOf(listOf("acc", "d") to listOf("durations", "(ZERO)")), lambdasIn("durations.fold(ZERO) { acc, d -> acc + d }"))
        assertEquals(listOf(listOf("name", "duration") to listOf("pairs")), lambdasIn("pairs?.forEach { (name, duration) -> }"))
    }

    @Test
    fun `the blocks of control flow and of declarations are no lambdas`() {
        val code = """
            fun f(): Int {
                if (a) { b() } else { c() }
                when (x) {
                    1 -> { d() }
                }
                try { e() } catch (e: Exception) { } finally { }
                for (i in xs) { }
                while (g()) { }
                val o = object : Runnable { }
                class A(val x: Int) { }
                return 1
            }
        """
        assertEquals(emptyList(), lambdasIn(code))
    }

    @Test
    fun `a lambda passed in another way has no inputs, and parameters with types are not read`() {
        assertEquals(listOf(emptyList<String>() to emptyList<String>()), lambdasIn("val f = { x: Int -> x }"))
        assertEquals(listOf(listOf("it") to emptyList<String>()), lambdasIn("call(1, { it })"))
    }
}
