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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [ReferenceLabels], the check of the stars in a line of the reference. The labels in the form that the website
 * generator reads pass, and each way to break the form is tried once to see it reported with one message.
 */
class ReferenceLabelsTest {

    @Test
    fun `labels at the start of a line, behind a blank and behind an opening bracket pass`() {
        listOf(
            "*Fit*",
            "A corner holds the button *Fit*.",
            "Choose *Run > Start Runtime*, or (*Fit*) in a corner.",
            "*Move earlier, to priority N* and *Open page NAME*",
            "Two labels are two pairs: *Add target*, *Add source*.",
            "- *Stop*",
            "   *Remove breakpoint at end* instead.",
        ).forEach { line -> assertEquals(emptyList(), ReferenceLabels.violationsIn(line), line) }
    }

    @Test
    fun `labels in table cells and around a link pass`() {
        assertEquals(emptyList(), ReferenceLabels.violationsIn("| *File* | *Open Folder…* | Opens a folder. |"))
        assertEquals(emptyList(), ReferenceLabels.violationsIn("|*Save*|*Cancel*|"))
        assertEquals(emptyList(), ReferenceLabels.violationsIn("See *[Fit](4.3-diagrams.md)* there."))
    }

    @Test
    fun `stars in code and bold are not looked at`() {
        assertEquals(emptyList(), ReferenceLabels.violationsIn("A file `*.kmeta`, the value `\"*\"` and ` [*]` behind it."))
        assertEquals(emptyList(), ReferenceLabels.violationsIn("**bold** is the business of another check"))
    }

    @Test
    fun `a star that is not where a label opens or closes is refused`() {
        listOf(
            "a*Fit* without a blank in front",
            "* Fit* with a blank behind the opening star",
            "*Fit * with a blank before the closing star",
            "a lone * star",
            "*Run > Start that does not close",
        ).forEach { line -> assertEquals(1, ReferenceLabels.violationsIn(line).size, line) }
    }

    @Test
    fun `a label with code inside and a link with the stars inside are refused`() {
        assertEquals(1, ReferenceLabels.violationsIn("*Open `x` here*").size)
        assertEquals(1, ReferenceLabels.violationsIn("[*Fit*](4.3-diagrams.md)").size)
    }

    @Test
    fun `a heading holds no star`() {
        assertEquals(1, ReferenceLabels.violationsIn("## 4.3.1 The button *Fit*").size)
        assertEquals(emptyList(), ReferenceLabels.violationsIn("## 4.3.1 Views"))
    }
}
