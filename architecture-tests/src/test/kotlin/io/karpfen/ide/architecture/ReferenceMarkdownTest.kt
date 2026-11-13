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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [ReferenceMarkdown], the check of the form of a reference file. A file in the form passes, and each rule is
 * broken once to see it reported. The inputs write the typographic characters as escapes, so this file holds none of
 * them.
 */
class ReferenceMarkdownTest {

    /** The file 4.3-diagrams.md in the form of the reference. */
    private val good = """
        # 4.3 Diagrams

        Text with `TRANSITION "a" -> "b" { }` in code.

        ## 4.3.1 Views

        ### 4.3.1.1 Moving around

        ```kstates
        # not a heading
        ```

        ## 4.3.2 Reading a statechart

        1. A step with code:

           ```text
           output -> here
           ```
    """.trimIndent()

    /** The violations of [text] as the file `4.3-diagrams.md`. */
    private fun violations(text: String): List<String> = ReferenceMarkdown.violationsIn("4.3-diagrams.md", text)

    @Test
    fun `a file in the form of the reference passes, and so does a chapter head without subheadings`() {
        assertEquals(emptyList(), violations(good))
        assertEquals(emptyList(), ReferenceMarkdown.violationsIn("4-ide.md", "# 4 The IDE\n\nA paragraph.\n"))
    }

    @Test
    fun `the number of a file comes from its name`() {
        assertEquals("4.3", ReferenceMarkdown.numberOf("4.3-diagrams.md"))
        assertEquals("1", ReferenceMarkdown.numberOf("1-getting-started.md"))
        assertNull(ReferenceMarkdown.numberOf("README.md"))
        assertNull(ReferenceMarkdown.numberOf("getting-started.md"))
    }

    @Test
    fun `the first heading carries the number of the file`() {
        assertTrue(violations(good.replace("# 4.3 Diagrams", "# 4.2 Diagrams")).single().startsWith("line 1:"))
        assertTrue(violations(good.replace("# 4.3 Diagrams", "## 4.3 Diagrams")).isNotEmpty())
    }

    @Test
    fun `headings are numbered in order, at the right level and at most four parts deep`() {
        assertEquals(1, violations(good.replace("## 4.3.2 Reading", "## Reading")).size, "a heading without a number")
        assertEquals(1, violations(good.replace("## 4.3.2 Reading", "## 4.3.3 Reading")).size, "a gap")
        assertEquals(1, violations(good.replace("## 4.3.2 Reading", "## 4.3.1 Reading")).size, "a repeat")
        assertEquals(1, violations(good.replace("### 4.3.1.1 Moving", "## 4.3.1.1 Moving")).size, "the wrong level")
        assertEquals(1, violations(good.replace("## 4.3.2 Reading", "## 4.2.2 Reading")).size, "outside the section")
        assertEquals(1, violations(good.replace("## 4.3.2 Reading", "#### 4.3.1.1.1 Reading")).size, "too deep")
        assertEquals(1, violations(good.replace("## 4.3.2 Reading a statechart", "## 4.3.2 Reading:")).size, "a colon")
    }

    @Test
    fun `every code block names a language of the reference`() {
        assertEquals(1, violations(good.replace("```kstates", "```")).size)
        assertEquals(1, violations(good.replace("```kstates", "```java")).size)
        assertEquals(1, violations("$good\n\n```text\nopen").size, "a code block that is not closed")
    }

    @Test
    fun `prose does without bold, dashes, middle dots and arrows outside code`() {
        listOf("**bold**", "__bold__", "a — b", "a – b", "a · b", "a → b", "a -> b", "a => b").forEach { prose ->
            assertEquals(1, violations("$good\n\n$prose\n").size, prose)
        }
        assertEquals(emptyList(), violations("$good\n\nThe arrow `->` in code and a hyphen-joined word.\n"))
    }
}
