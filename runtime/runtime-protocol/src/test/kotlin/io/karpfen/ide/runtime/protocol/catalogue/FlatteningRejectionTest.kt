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
package io.karpfen.ide.runtime.protocol.catalogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Covers [FlatteningRejection]. The body of an activation refused for state machines that do not flatten parses into
 * the environment and its problems, each with the state machine it belongs to, and renders back to the same text. A
 * message may hold quotes and colons. Any text that deviates from the form parses to null, and the values refuse what
 * the form cannot express.
 */
class FlatteningRejectionTest {

    @Test
    fun `the body of a refused activation parses into its problems and renders back to the same text`() {
        val body = "Environment with key env-1 has state machines that cannot be flattened:\n" +
            "State machine for 'lamp': $MISSING\n" +
            "Attachable state machine 'glowing': $MACRO"

        val rejection = FlatteningRejection.parse(body)

        assertEquals(
            FlatteningRejection(
                "env-1",
                listOf(
                    FlatteningProblemLine(UnflattenedMachine.Attached("lamp"), MISSING),
                    FlatteningProblemLine(UnflattenedMachine.Attachable("glowing"), MACRO),
                ),
            ),
            rejection,
        )
        assertEquals(body, rejection?.render())
    }

    @Test
    fun `a key with blanks and quotes is read up to the first quote that a colon follows`() {
        val body = "Environment with key env-7 has state machines that cannot be flattened:\nState machine for 'living room's lamp': $MISSING"

        assertEquals(UnflattenedMachine.Attached("living room's lamp"), FlatteningRejection.parse(body)?.problems?.single()?.machine)
    }

    @Test
    fun `a text that deviates from the form parses to null`() {
        val line = "State machine for 'lamp': $MISSING"
        listOf(
            "",
            "Environment with key env-1 has state machines that cannot be flattened:",
            "Environment with key env-1 has state machines that cannot be flattened:\n",
            "Environment with key env-1 has state machines that cannot be flattened:\n$line\n",
            "Environment with key env-1 has state machines that cannot be flattened:\n\n$line",
            "Environment with key env 1 has state machines that cannot be flattened:\n$line",
            "Environment with key env-1 has state machines that cannot be flattened\n$line",
            "Environment with key env-1 has state machines that cannot be flattened:\nState machine 'lamp': $MISSING",
            "Environment with key env-1 has state machines that cannot be flattened:\nState machine for 'lamp': ",
            " Environment with key env-1 has state machines that cannot be flattened:\n$line",
        ).forEach { body ->
            assertNull(FlatteningRejection.parse(body), body)
        }
    }

    @Test
    fun `the values refuse what the body cannot express`() {
        assertFailsWith<IllegalArgumentException> { FlatteningRejection("env-1", emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            FlatteningRejection("env 1", listOf(FlatteningProblemLine(UnflattenedMachine.Attached("lamp"), MISSING)))
        }
        assertFailsWith<IllegalArgumentException> { FlatteningProblemLine(UnflattenedMachine.Attached("lamp"), "") }
        assertFailsWith<IllegalArgumentException> { FlatteningProblemLine(UnflattenedMachine.Attached("lamp"), "two\nlines") }
        assertFailsWith<IllegalArgumentException> { UnflattenedMachine.Attached("two\nlines") }
        assertFailsWith<IllegalArgumentException> { UnflattenedMachine.Attachable("two\nlines") }
    }

    private companion object {
        /** The message of dsl-tools for a container state that uses a fragment no state machine has. */
        const val MISSING = "Container state 'self test' of the state machine attached to 'Lamp' uses 'bulb test', but no " +
            "fragment is named 'bulb test'"

        /** The message of dsl-tools for a macro call that no file of a composition defines. */
        const val MACRO = "No file of the composition of state machine 'glowing' defines a macro named 'warm: up'"
    }
}
