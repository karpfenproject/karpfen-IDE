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
import io.karpfen.ide.notation.diagram.structure.MetamodelProjection
import io.karpfen.ide.notation.diagram.structure.ModelProjection
import io.karpfen.ide.notation.diagram.structure.StructureDiagram

/**
 * The metamodel and the model the tests of the class and object diagram work on: a room with a lamp and a wall
 * switch. The lamp has two bulbs, so two links connect the same two types, and it knows the switch.
 */
internal object LampStructure {

    /** The types of the lamp. `Shade` is named by a relation but not declared. */
    val METAMODEL = """
        type "Bulb" "A light bulb" {
            prop("maxWatts", "number")
            prop("lit", "boolean")
        }
        type "Switch" "A wall switch" {
            prop("presses", list("string"))
        }
        type "Lamp" "A lamp" {
            prop("mode", "string")
            has("bulb", "Bulb")
            has("spare", "Bulb")
            has("shade", "Shade")
            knows("control", "Switch")
        }
        type "Room" "A room" {
            has("lamps", list("Lamp"))
            has("switch", "Switch")
        }
    """.trimIndent()

    /** A living room with one lamp. The lamp knows the wall switch and an object that does not exist. */
    val MODEL = """
        make object "living room":"Room" {
            has("lamps") -> make object "lamp":"Lamp" {
                prop("mode") -> "off"
                has("bulb") -> make object "bulb":"Bulb" {
                    prop("maxWatts") -> "40"
                }
                has("spare") -> make object "_":"Bulb" {
                    prop("maxWatts") -> "60"
                }
                knows("control") -> "wallSwitch"
                knows("fuse") -> "fuseBox"
            }
            has("switch") -> make object "wallSwitch":"Switch" { }
        }
    """.trimIndent()

    /** The class diagram of the metamodel [text]. */
    fun typesOf(text: String): StructureDiagram =
        MetamodelProjection.of(defaultLanguageRegistry().support(KarpfenLanguage.KMETA).parser.parse(TextSnapshot(text, 0)).outline, text)

    /** The object diagram of the model [text]. */
    fun objectsOf(text: String): StructureDiagram =
        ModelProjection.of(defaultLanguageRegistry().support(KarpfenLanguage.KMODEL).parser.parse(TextSnapshot(text, 0)).outline)
}
