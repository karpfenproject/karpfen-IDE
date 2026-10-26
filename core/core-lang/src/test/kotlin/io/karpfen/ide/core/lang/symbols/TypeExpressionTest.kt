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
