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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.ExampleContext
import io.karpfen.ide.core.document.fixtures.ExampleFile
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.lang.fixtures.at
import io.karpfen.ide.core.lang.fixtures.textAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the names of fragments after `USE`: completion with the fragment's own name left out, hover, go to the header
 * of the fragment in another file and find references across the files.
 */
class UsableFragmentsTest {

    private val analysis = ExampleProject.analyze(listOf(ExampleContext(DIRECTORY, FILES)))
    private val probe = ServicesProbe(analysis)

    private fun document(name: String): LanguageDocument =
        ExampleProject.document(ExampleProject.pathOf("$DIRECTORY/$name"), FILES.single { it.fileName == name }.text)

    @Test
    fun `fragment names complete after USE, without the fragment that asks`() {
        val lamp = document("lamp.kstates")
        val test = document("bulb test.frag.kstates")

        val items = probe.complete(lamp, after(lamp.text.text, "USE \""))
        assertEquals(listOf("bulb test", "wiring"), items.map { it.label })
        assertTrue(items.all { it.kind == CompletionKind.STATE_MACHINE })
        assertEquals("bulb test.frag.kstates", items.first().detail)
        assertEquals(listOf("wiring"), probe.labels(test, after(test.text.text, "USE \"")))
    }

    @Test
    fun `a fragment name hovers with its file and leads to the header of the fragment`() {
        val lamp = document("lamp.kstates")
        val test = document("bulb test.frag.kstates")

        val hover = assertNotNull(probe.hover(lamp, at(lamp.text.text, "\"bulb test\"", 2)))
        assertEquals("fragment \"bulb test\" in bulb test.frag.kstates", hover.text)
        val target = probe.definition(lamp, at(lamp.text.text, "\"bulb test\"", 2)).single()
        assertEquals(test.path, target.path)
        assertEquals("bulb test", textAt(test.text.text, target))
    }

    @Test
    fun `find references on a USE name lists the header and every USE of the fragment`() {
        val test = document("bulb test.frag.kstates")

        val places = probe.references(test, at(test.text.text, "\"wiring\"", 2))

        assertEquals(listOf("bulb test.frag.kstates", "wiring.frag.kstates"), places.map { it.path.fileName.toString() }.distinct().sorted())
    }

    @Test
    fun `the keywords of pages are offered where they stand`() {
        val lamp = document("lamp.kstates")

        assertTrue("DONE" in probe.labels(lamp, after(lamp.text.text, "CONDITION { ")))
        assertTrue("CONTAINER STATE \"…\" USE \"…\" { }" in probe.labels(lamp, after(lamp.text.text, "STATES {\n")))
    }

    private companion object {
        const val DIRECTORY = "karpfen-dsl-tools/pages"

        private fun file(name: String, text: String) = ExampleFile("$DIRECTORY/$name", KarpfenLanguage.KSTATES, text.trimIndent() + "\n")

        val FILES = listOf(
            ExampleFile("$DIRECTORY/lamp.kmeta", KarpfenLanguage.KMETA, "type \"Lamp\" \"A lamp\" {\n    prop(\"lit\", \"boolean\")\n}\n"),
            file(
                "lamp.kstates",
                """
                STATEMACHINE ATTACHED TO "Lamp" {
                    STATES {
                        INITIAL STATE "off" { }
                        CONTAINER STATE "self test" USE "bulb test" { }
                    }
                    TRANSITIONS {
                        TRANSITION "off" -> "self test" { }
                        TRANSITION "self test" -> "off" { CONDITION { DONE } }
                    }
                }
                """,
            ),
            file(
                "bulb test.frag.kstates",
                """
                STATEMACHINE "bulb test" FRAGMENT {
                    STATES {
                        INITIAL CONTAINER STATE "check" USE "wiring" { }
                        FINAL STATE "passed" { }
                    }
                    TRANSITIONS {
                        TRANSITION "check" -> "passed" { CONDITION { DONE } }
                    }
                }
                """,
            ),
            file(
                "wiring.frag.kstates",
                """
                STATEMACHINE "wiring" FRAGMENT {
                    STATES {
                        INITIAL STATE "measure" { }
                        FINAL STATE "ok" { }
                    }
                    TRANSITIONS {
                        TRANSITION "measure" -> "ok" { }
                    }
                }
                """,
            ),
        )
    }
}
