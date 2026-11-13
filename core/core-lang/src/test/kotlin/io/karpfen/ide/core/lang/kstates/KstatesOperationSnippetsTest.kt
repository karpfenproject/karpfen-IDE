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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.lang.completion.Snippet
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The operation snippets of KStates completion agree with the readers of dsl-tools: each snippet, filled in the way a
 * user fills it, is a rule with the operands dsl-tools expects of its operation, so it reads without a finding.
 */
class KstatesOperationSnippetsTest {

    @Test
    fun `every operation snippet, filled in, reads without a finding`() {
        val reader = DslReader()
        val snippets = KstatesKeywords.at(KstatesPosition.ACTIONS, KstatesFeature.entries.toSet())
            .filter { it.label.substringBefore('(') in KarpfenSyntax.KSTATES_OPERATIONS }

        assertEquals(KarpfenSyntax.KSTATES_OPERATIONS.size, snippets.size, "one snippet per operation")
        snippets.forEach { snippet ->
            val rule = filledIn(snippet.template)

            val read = reader.kstates(TextSnapshot(machineWith(rule), 0))

            assertEquals(emptyList(), read.diagnostics, rule)
        }
    }

    /** [template] with a path where the caret goes, a relation in its empty string and a value after its last comma. */
    private fun filledIn(template: String): String =
        template.replace(Snippet.CARET, "p").replace("\"\"", "\"r\"").replace(", )", ", \"v\")")

    /** A state machine whose initial state runs [rule] on entry. */
    private fun machineWith(rule: String): String =
        "STATEMACHINE ATTACHED TO \"T\" {\n    STATES {\n        INITIAL STATE \"s\" {\n            ENTRY {\n" +
            "                $rule\n            }\n        }\n    }\n    TRANSITIONS { }\n}\n"
}
