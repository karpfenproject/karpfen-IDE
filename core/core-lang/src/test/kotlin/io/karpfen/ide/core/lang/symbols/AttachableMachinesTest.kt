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

import io.karpfen.dsl.states.KstatesFeature
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
 * Covers the names of attachable state machines across the files of a context: completion after `ATTACHABLE TO`,
 * after `behaves after`, after `ATTACH` and as the second operand of `EXCHANGE`, the ranking by the suited type, hover,
 * go to definition and find references in KMeta and KStates, and the constructs that are not offered while the feature
 * is off.
 */
class AttachableMachinesTest {

    private val analysis = ExampleProject.analyze(listOf(ExampleContext(DIRECTORY, FILES)))
    private val probe = ServicesProbe(analysis)

    private fun document(name: String, edit: (String) -> String = { it }): LanguageDocument =
        ExampleProject.document(ExampleProject.pathOf("$DIRECTORY/$name"), edit(FILES.single { it.fileName == name }.text))

    @Test
    fun `the context reads without findings`() {
        assertEquals(emptyMap(), analysis.diagnostics)
    }

    @Test
    fun `the header of an attachable state machine completes the types`() {
        val glowing = document("glowing.kstates")

        assertEquals(listOf("Bulb", "Lamp", "Room"), probe.labels(glowing, after(glowing.text.text, "ATTACHABLE TO \"")))
    }

    @Test
    fun `machine names complete after behaves after, ATTACH and EXCHANGE, the suited ones first`() {
        val metamodel = document("spares.kmeta")
        val room = document("room.kstates")

        val behaves = probe.complete(metamodel, after(metamodel.text.text, "behaves after \""))
        assertEquals(listOf("glowing", "lamplight"), behaves.map { it.label })
        assertTrue(behaves.all { it.kind == CompletionKind.STATE_MACHINE })
        assertEquals("Bulb", behaves.first().detail)
        assertEquals(listOf("glowing", "lamplight"), probe.labels(room, after(room.text.text, "ATTACH \"")))
        assertEquals(listOf("glowing", "lamplight"), probe.labels(room, after(room.text.text, "EXCHANGE(\"lamp->bulb\", \"")))
    }

    @Test
    fun `a type header offers behaves after, an object rule ATTACH and an action block EXCHANGE`() {
        val metamodel = document("spares.kmeta")
        val room = document("room.kstates") { it.replace(" ATTACH \"glowing\"", " ") }

        assertTrue("behaves after \"…\"" in probe.labels(metamodel, after(metamodel.text.text, "\"A lamp\" ")))
        assertTrue("ATTACH \"…\"" in probe.labels(room, after(room.text.text, "\"lamp->bulb->watts\")) ")))
        assertTrue("EXCHANGE(\"…\", …)" in probe.labels(room, after(room.text.text, "ENTRY {\n")))
    }

    @Test
    fun `while the feature is off none of its constructs is offered`() {
        val off = ServicesProbe(analysis.copy(languageFeatures = KstatesFeature.ALL - KstatesFeature.ATTACHMENT))
        val metamodel = document("spares.kmeta")
        val room = document("room.kstates") { it.replace(" ATTACH \"glowing\"", " ") }

        assertTrue(off.labels(metamodel, after(metamodel.text.text, "\"A lamp\" ")).none { it.startsWith("behaves") })
        assertTrue(off.labels(room, after(room.text.text, "\"lamp->bulb->watts\")) ")).none { it.startsWith("ATTACH") })
        assertTrue(off.labels(room, after(room.text.text, "ENTRY {\n")).none { it.startsWith("EXCHANGE") })
    }

    @Test
    fun `a machine name hovers with its types and navigates to its declaration in another file`() {
        val metamodel = document("spares.kmeta")
        val room = document("room.kstates")
        val glowing = document("glowing.kstates")

        val hover = assertNotNull(probe.hover(room, at(room.text.text, "\"glowing\"", 2)))
        assertEquals("state machine \"glowing\" attachable to Bulb", hover.text)
        val target = probe.definition(metamodel, at(metamodel.text.text, "\"glowing\"", 2)).single()
        assertEquals(glowing.path, target.path)
        assertEquals("glowing", textAt(glowing.text.text, target))
        assertEquals(target, probe.definition(room, at(room.text.text, "\"glowing\"", 2)).single())
    }

    @Test
    fun `find references lists every place across the context that names the machine`() {
        val glowing = document("glowing.kstates")

        val places = probe.references(glowing, at(glowing.text.text, "\"glowing\"", 2))

        assertEquals(
            listOf("glowing.kstates", "room.kstates", "spares.kmeta"),
            places.map { it.path.fileName.toString() }.distinct().sorted(),
        )
    }

    private companion object {
        const val DIRECTORY = "karpfen-dsl-tools/spares"

        private fun file(name: String, language: KarpfenLanguage, text: String) = ExampleFile("$DIRECTORY/$name", language, text.trimIndent() + "\n")

        val FILES = listOf(
            file(
                "spares.kmeta",
                KarpfenLanguage.KMETA,
                """
                type "Bulb" "A bulb" behaves after "glowing" {
                    prop("watts", "number")
                    prop("lit", "boolean")
                }
                type "Lamp" "A lamp" {
                    has("bulb", "Bulb")
                }
                type "Room" "A room" {
                    has("lamp", "Lamp")
                    has("spares", list("Bulb"))
                }
                """,
            ),
            file(
                "spares.kmodel",
                KarpfenLanguage.KMODEL,
                """
                make object "room":"Room" {
                    has("lamp") -> make object "lamp":"Lamp" {
                        has("bulb") -> make object "bulb":"Bulb" {
                            prop("watts") -> "40"
                            prop("lit") -> "false"
                        }
                    }
                }
                """,
            ),
            file(
                "glowing.kstates",
                KarpfenLanguage.KSTATES,
                """
                STATEMACHINE "glowing" ATTACHABLE TO "Bulb" {
                    STATES {
                        INITIAL STATE "dark" { }
                    }
                    TRANSITIONS { }
                }
                """,
            ),
            file(
                "lamplight.kstates",
                KarpfenLanguage.KSTATES,
                """
                STATEMACHINE "lamplight" ATTACHABLE TO "Lamp" {
                    STATES {
                        INITIAL STATE "dark" { }
                    }
                    TRANSITIONS { }
                }
                """,
            ),
            file(
                "room.kstates",
                KarpfenLanguage.KSTATES,
                """
                STATEMACHINE ATTACHED TO "Room" {
                    STATES {
                        INITIAL STATE "ready" {
                            ENTRY {
                                APPENDOBJ("self", "spares", MACRO("new bulb", "lamp->bulb->watts")) ATTACH "glowing"
                                EXCHANGE("lamp->bulb", "glowing")
                            }
                        }
                    }
                    TRANSITIONS { }
                    MACROS {
                        MACRO "new bulb" {
                            TAKES("watts", "number")
                            RETURNS("Bulb")
                            DEFINITION {
                                EVAL { return {"watts": ${'$'}(watts), "lit": False} }
                            }
                        }
                    }
                }
                """,
            ),
        )
    }
}
