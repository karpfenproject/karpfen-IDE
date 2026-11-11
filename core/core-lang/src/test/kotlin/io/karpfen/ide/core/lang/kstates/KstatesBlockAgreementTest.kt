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
package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.caret
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers that highlighting and completion read KStates blocks by one rule: `EVENT` is highlighted as an operation
 * exactly where the action keywords are completed, also in broken text.
 */
class KstatesBlockAgreementTest {

    private val path = Path.of("/ws/m.kstates")

    /** The token kind the tokenizer gives an `EVENT(…)` written at the caret, and the slot of the caret itself. */
    private fun readings(textWithCaret: String): Pair<TokenKind, KstatesSlot> {
        val (text, offset) = caret(textWithCaret)
        val withEvent = text.substring(0, offset) + EVENT_CALL + text.substring(offset)
        val tokens = ExampleProject.document(path, withEvent).tokens.tokens
        val event = tokens.single { withEvent.substring(it.range.start, it.range.end) == "EVENT" }
        val slot = KstatesSlots(CaretContextDetector()).at(ExampleProject.document(path, text), offset).second
        return event.kind to slot
    }

    private fun machine(actions: String) =
        "STATEMACHINE ATTACHED TO \"T\" {\n STATES {\n  INITIAL STATE \"s\" {\n   DO {\n    $actions\n   }\n  }\n }\n}\n"

    @Test
    fun `EVENT is an operation exactly where actions are completed`() {
        val cases = mapOf(
            machine("‸") to KstatesPosition.ACTIONS,
            machine("WITH MACRO(\"m\", \"a\") AS \"x\" { ‸ }") to KstatesPosition.ACTIONS,
            machine("IF IN SCOPE(\"p\") { ‸ }") to KstatesPosition.ACTIONS,
            machine("WITH MACRO { ‸ }") to KstatesPosition.MACRO_BODY,
            machine("IF IN SCOPE(STATE) { ‸ }") to KstatesPosition.ACTIONS,
            "STATEMACHINE ATTACHED TO \"T\" { TRANSITIONS { TRANSITION \"a\" -> \"b\" { CONDITION { ‸ } } } }" to KstatesPosition.CONDITION,
            "STATEMACHINE ATTACHED TO \"T\" { MACROS { MACRO \"m\" { ‸ } } }" to KstatesPosition.MACRO_BODY,
        )

        for ((text, position) in cases) {
            val (event, slot) = readings(text)
            assertEquals(KstatesSlot.Keywords(position), slot, text)
            assertEquals(if (position == KstatesPosition.ACTIONS) TokenKind.OPERATION else TokenKind.KEYWORD, event, text)
        }
    }

    private companion object {
        const val EVENT_CALL = "EVENT(\"d\", \"n\")"
    }
}
