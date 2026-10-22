package io.karpfen.ide.core.lang.symbols

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers reading types as written in karpfen sources. */
class TypeExpressionTest {

    @Test
    fun `plain, list and reference types are read as element type and list flag`() {
        assertEquals(TypeExpression("Vector", isList = false), TypeExpression.parse("Vector"))
        assertEquals(TypeExpression("Obstacle", isList = true), TypeExpression.parse("list(Obstacle)"))
        assertEquals(TypeExpression("Obstacle", isList = false), TypeExpression.parse(" reference( Obstacle ) "))
        assertEquals(TypeExpression("string", isList = true), TypeExpression.parse("list(string)"))
    }

    @Test
    fun `missing and broken types are read leniently`() {
        assertEquals(TypeExpression("", isList = false), TypeExpression.parse(null))
        assertEquals(TypeExpression("list(", isList = false), TypeExpression.parse("list("))
        assertTrue(TypeExpression.parse("number").isPrimitive)
        assertFalse(TypeExpression.parse("Number").isPrimitive)
    }
}
