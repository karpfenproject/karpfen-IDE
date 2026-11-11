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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.runtime.session.mirror.Containment
import io.karpfen.ide.runtime.session.mirror.MirrorObject
import io.karpfen.ide.runtime.session.mirror.MirrorProperty
import io.karpfen.ide.runtime.session.mirror.MirrorRelation
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.ObjectOrigin
import io.karpfen.ide.runtime.session.mirror.ObjectRef
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.states.ActiveStates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the model tree of the Model tab over a mirror built by hand. A house holds its kitchen through containment and
 * knows its owner. The kitchen has an anonymous lamp. The owner was created by the runtime, and no object holds it.
 */
class ModelTreeTest {

    @Test
    fun `the roots and the objects no one holds are the top level, closed`() {
        assertEquals(
            listOf(house(expanded = false), bob()),
            ModelTreeProjection.rows(mirror(), emptySet(), null),
        )
    }

    @Test
    fun `an open object shows its properties, the objects it holds and links to the objects it only knows`() {
        assertEquals(
            listOf(
                house(expanded = true),
                ModelTreeRow.Property("p:o:house/name", 1, "name", "\"Villa\""),
                kitchen(expanded = false),
                ModelTreeRow.Link("l:o:house/owner", 1, "owner", listOf("bob")),
                bob(),
            ),
            ModelTreeProjection.rows(mirror(), setOf(HOUSE), null),
        )
        assertEquals(
            listOf(
                house(expanded = true),
                ModelTreeRow.Property("p:o:house/name", 1, "name", "\"Villa\""),
                kitchen(expanded = true),
                ModelTreeRow.Property("p:o:kitchen/area", 2, "area", "12"),
                ModelTreeRow.Object("o:kitchen/lamps#0", 2, "", "Lamp", "lamps", false, true, false, false),
                ModelTreeRow.Link("l:o:house/owner", 1, "owner", listOf("bob")),
                bob(),
            ),
            ModelTreeProjection.rows(mirror(), setOf(HOUSE, KITCHEN_KEY), null),
        )
    }

    @Test
    fun `values read as words`() {
        assertEquals("12", ModelTreeProjection.text(PropertyValue.Single(ScalarValue.Number(12.0))))
        assertEquals("2.5", ModelTreeProjection.text(PropertyValue.Single(ScalarValue.Number(2.5))))
        assertEquals("true", ModelTreeProjection.text(PropertyValue.Single(ScalarValue.Flag(true))))
        assertEquals(
            "[\"a\", \"b\"]",
            ModelTreeProjection.text(PropertyValue.Many(listOf(ScalarValue.Text("a"), ScalarValue.Text("b")))),
        )
    }

    @Test
    fun `selecting an object opens its owners, and what is open stays open across updates of the model`() = runTest {
        val model = MutableStateFlow(observed(mirror()))
        val tree = ModelTreeState(backgroundScope, model)
        runCurrent()

        tree.select("kitchen")
        runCurrent()

        assertEquals(listOf(HOUSE, KITCHEN_KEY), tree.rows.value.filterIsInstance<ModelTreeRow.Object>().take(2).map { it.key })
        assertEquals(KITCHEN_KEY, tree.rows.value.filterIsInstance<ModelTreeRow.Object>().single { it.selected }.key)

        tree.toggle(KITCHEN_KEY)
        model.value = observed(mirror(area = 13.0))
        runCurrent()

        val kitchen = tree.rows.value.filterIsInstance<ModelTreeRow.Object>().single { it.key == KITCHEN_KEY }
        assertEquals(true, kitchen.expanded)
        assertEquals(true, kitchen.selected)
        assertEquals("13", tree.rows.value.filterIsInstance<ModelTreeRow.Property>().single { it.name == "area" }.value)
    }

    private fun house(expanded: Boolean) = ModelTreeRow.Object(HOUSE, 0, "house", "House", null, false, true, expanded, false)

    private fun kitchen(expanded: Boolean) = ModelTreeRow.Object(KITCHEN_KEY, 1, "kitchen", "Room", "rooms", false, true, expanded, false)

    private fun bob() = ModelTreeRow.Object("o:bob", 0, "bob", "Person", null, true, true, false, false)

    /** The mirror of the house with the kitchen's [area]. */
    private fun mirror(area: Double = 12.0): MirrorSnapshot {
        val lamp = MirrorObject("", "Lamp", listOf(property("on", ScalarValue.Flag(true))), emptyList(), ObjectOrigin.MODEL, 0)
        val house = MirrorObject(
            "house",
            "House",
            listOf(property("name", ScalarValue.Text("Villa"))),
            listOf(relation("rooms", ObjectRef.Keyed("kitchen")), relation("owner", ObjectRef.Keyed("bob"))),
            ObjectOrigin.MODEL,
            0,
        )
        val kitchen = MirrorObject(
            "kitchen",
            "Room",
            listOf(property("area", ScalarValue.Number(area))),
            listOf(relation("lamps", ObjectRef.Anonymous(lamp))),
            ObjectOrigin.MODEL,
            0,
        )
        val bob = MirrorObject("bob", "Person", listOf(property("name", ScalarValue.Text("Bob"))), emptyList(), ObjectOrigin.RUNTIME, 1)
        return MirrorSnapshot(
            objects = linkedMapOf("house" to house, "kitchen" to kitchen, "bob" to bob),
            roots = listOf(ObjectRef.Keyed("house")),
            containment = mapOf("kitchen" to Containment("house", "rooms")),
        )
    }

    private fun property(key: String, value: ScalarValue) = MirrorProperty(key, PropertyValue.Single(value))

    private fun relation(key: String, target: ObjectRef) = MirrorRelation(key, null, false, listOf(target))

    private fun observed(mirror: MirrorSnapshot) =
        ObservedModel(ObservationMode.ON, SyncStatus.Predicted, null, mirror, ActiveStates(emptyMap(), emptyMap()))

    private companion object {
        const val HOUSE = "o:house"
        const val KITCHEN_KEY = "o:kitchen"
    }
}
