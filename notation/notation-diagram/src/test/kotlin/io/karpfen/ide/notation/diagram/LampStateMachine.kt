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
package io.karpfen.ide.notation.diagram

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection

/**
 * The state machines the diagram tests work on. [LAMP] is a lamp with a dimmer, with states nested in "on" and
 * transitions that start at the parent state. [FORK] has a split and a join.
 */
internal object LampStateMachine {

    /** A lamp that is switched, dimmed and smashed. */
    val LAMP = """
        STATEMACHINE ATTACHED TO "Lamp" {
            STATES {
                INITIAL STATE "off" {
                    ENTRY { SET("mode", "off") }
                }
                STATE "on" {
                    ENTRY { SET("mode", "on") }
                    STATE "full" { }
                    STATE "brighter" {
                        ENTRY { SET("bulb->watts", EVAL { return ${'$'}(bulb->watts) + 10 }) }
                    }
                }
                STATE "broken" { }
            }
            TRANSITIONS {
                TRANSITION "off" -> "full" {
                    CONDITION { EVENT("switch", "press") }
                }
                TRANSITION "on" -> "off" {
                    CONDITION { EVENT("switch", "press") }
                }
                TRANSITION "on" -> "brighter" NOT LOOPING {
                    CONDITION {
                        EVENT("switch", "dim")
                        VALUE("event->bright")
                        EVAL { return ${'$'}(bulb->watts) + 10 <= ${'$'}(bulb->maxWatts) }
                    }
                }
                TRANSITION "off" -> "broken" {
                    CONDITION { EVENT("service", "smash") }
                }
                TRANSITION "off" -> "broken" { }
            }
        }
    """.trimIndent()

    /** A machine that splits into two states and joins them again. */
    val FORK = """
        STATEMACHINE ATTACHED TO "Job" {
            STATES {
                INITIAL STATE "idle" { }
                STATE "left" { }
                STATE "right" { }
                STATE "done" { }
            }
            TRANSITIONS {
                SPLIT "idle" -> "left", "right" {
                    CONDITION { EVENT("job", "start") }
                }
                JOIN "left", "right" -> "done" { }
            }
        }
    """.trimIndent()

    /** The diagram of the state machine [text]. */
    fun diagramOf(text: String): StatechartDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(TextSnapshot(text, 0))
        check(outcome.isSyntaxClean) { "the test machine does not parse: ${outcome.syntaxDiagnostics}" }
        return checkNotNull(StatechartProjection.of(text, outcome.outline))
    }
}
