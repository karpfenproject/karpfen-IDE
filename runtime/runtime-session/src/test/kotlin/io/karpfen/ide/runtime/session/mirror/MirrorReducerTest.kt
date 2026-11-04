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
package io.karpfen.ide.runtime.session.mirror

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.session.examples.ReadModel
import io.karpfen.ide.runtime.session.examples.WireTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the mirror reducer with hand-written runtime JSON and the output of the runtime's writers: snapshots, pushes
 * of whole trees and of single objects, removal by absence, deletions with scrubbing, anonymous objects, unknown types
 * and keys, and origins.
 */
class MirrorReducerTest {

    private val lamp = ReadModel.synthesized(SynthesizedModel.LAMP)

    @Test
    fun `a snapshot of the deployed model replaces the mirror with exactly the seed`() {
        val step = lamp.reducer().replace(lamp.wireRoots(), revision = 0)

        assertEquals(lamp.seed, step.snapshot)
        assertEquals(lamp.seed.objects.keys, step.change.created)
        assertEquals(emptySet(), step.change.changed)
        assertEquals(emptySet(), step.change.deleted)
        assertEquals(emptyList(), step.anomalies)
    }

    @Test
    fun `every object of a snapshot carries the snapshot's revision`() {
        val snapshot = lamp.reducer().replace(lamp.wireRoots(), revision = 12).snapshot

        assertTrue(snapshot.objects.values.all { it.revision == 12L && it.origin == ObjectOrigin.MODEL })
    }

    @Test
    fun `the full turtle push upserts every keyed object of the pushed tree, the copies of known objects included`() {
        val robot = ReadModel.runtimeExample("single_robot_example")
        val written = robot.wire.objectJson("turtle")
        val pushed = written
            .replace(
                "{\"__id__\": \"turtlePosition\", \"__type__\": \"Vector\", \"x\": 5, \"y\": 5}",
                "{\"__id__\": \"turtlePosition\", \"__type__\": \"Vector\", \"x\": 5, \"y\": 5.3}",
            )
            .replace(
                "\"__id__\": \"chairBoundingBox\", \"__type__\": \"TwoDObject\", \"diameter\": 1,",
                "\"__id__\": \"chairBoundingBox\", \"__type__\": \"TwoDObject\", \"diameter\": 1.5,",
            )
        assertNotEquals(written, pushed)

        val step = robot.reducer().changed(robot.seed, WireTexts.changed("turtle", 58, pushed))

        assertEquals(setOf("turtlePosition", "chairBoundingBox"), step.change.changed)
        assertEquals(emptySet(), step.change.created)
        assertEquals(emptySet(), step.change.deleted)
        assertEquals(robot.seed.objects.keys - "APB 2101", step.snapshot.objects.filterValues { it.revision == 58L }.keys)
        assertEquals(0L, step.snapshot.objects.getValue("APB 2101").revision)
        assertEquals(PropertyValue.Single(ScalarValue.Number(5.3)), step.snapshot.objects.getValue("turtlePosition").property("y")?.value)
        assertEquals(PropertyValue.Single(ScalarValue.Number(1.5)), step.snapshot.objects.getValue("chairBoundingBox").property("diameter")?.value)
        assertEquals(robot.seed.containment, step.snapshot.containment)
        assertEquals(robot.seed.roots, step.snapshot.roots)
        assertEquals(emptyList(), step.anomalies)
    }

    @Test
    fun `a push of one object changes only that object and stamps it with the push's revision`() {
        val robot = ReadModel.runtimeExample("single_robot_example")

        val step = robot.reducer().changed(
            robot.seed,
            WireTexts.changed("turtlePosition", 58, "{\"__id__\": \"turtlePosition\", \"__type__\": \"Vector\", \"x\": 5, \"y\": 5.3}"),
        )

        assertEquals(MirrorChange(setOf("turtlePosition"), emptySet(), emptySet()), step.change)
        assertEquals(setOf("turtlePosition"), step.snapshot.objects.filterValues { it.revision == 58L }.keys)
        assertEquals(robot.seed.containment, step.snapshot.containment)
    }

    @Test
    fun `a push that writes an object as it is changes nothing, yet stamps the revision`() {
        val step = lamp.reducer().changed(lamp.seed, WireTexts.changed("bulb", 4, BULB))

        assertEquals(MirrorChange.NONE, step.change)
        assertEquals(4L, step.snapshot.objects.getValue("bulb").revision)
    }

    @Test
    fun `an object that appears twice in one push is taken at its first occurrence`() {
        val room = "{\"__id__\": \"living room\", \"__type__\": \"Room\", " +
            "\"lamp\": ${lampJson(control = "{\"__id__\": \"wallSwitch\", \"__type__\": \"Switch\", \"position\": \"up\"}")}, " +
            "\"switch\": {\"__id__\": \"wallSwitch\", \"__type__\": \"Switch\", \"position\": \"down\"}}"

        val step = lamp.reducer().changed(lamp.seed, WireTexts.changed("living room", 6, room))

        assertEquals(
            PropertyValue.Single(ScalarValue.Text("up")),
            step.snapshot.objects.getValue("wallSwitch").property("position")?.value,
        )
        assertEquals(Containment("living room", "switch"), step.snapshot.containment["wallSwitch"])
        assertEquals(setOf("wallSwitch"), step.change.changed)
    }

    @Test
    fun `objects the runtime creates appear inside their owner's push, and a child no longer held is removed`() {
        val depot = ReadModel.synthesized(SynthesizedModel.DEPOT)
        val reducer = depot.reducer()
        val crate0 = "{\"__id__\": \"crate0\", \"__type__\": \"Crate\", \"label\": \"initial\"}"

        val first = reducer.changed(depot.seed, WireTexts.changed("depot", 1, depotJson("stacked", crate0, crate(1, "stacked"))))
        val second = reducer.changed(first.snapshot, WireTexts.changed("depot", 2, depotJson("stacked", crate0, crate(1, "stacked"), crate(2, "stacked"))))
        val third = reducer.changed(second.snapshot, WireTexts.changed("depot", 3, depotJson("received", crate(3, "received"), crate(2, "stacked"))))
        val late = reducer.deleted(third.snapshot, WireTexts.deleted("crate0", 4))

        assertEquals(MirrorChange(setOf("depot"), setOf("gen_Crate_1"), emptySet()), first.change)
        assertEquals(ObjectOrigin.RUNTIME, first.snapshot.objects.getValue("gen_Crate_1").origin)
        assertEquals(Containment("depot", "stack"), first.snapshot.containment["gen_Crate_1"])
        assertEquals(MirrorChange(setOf("depot"), setOf("gen_Crate_2"), emptySet()), second.change)
        assertEquals(MirrorChange(setOf("depot"), setOf("gen_Crate_3"), setOf("crate0", "gen_Crate_1")), third.change)
        assertEquals(listOf("depot", "gen_Crate_2", "gen_Crate_3"), third.snapshot.objects.keys.toList())
        assertEquals(setOf("gen_Crate_2", "gen_Crate_3"), third.snapshot.containment.keys)
        assertEquals(ObjectOrigin.MODEL, third.snapshot.objects.getValue("depot").origin)
        assertEquals(MirrorChange.NONE, late.change)
        assertSame(third.snapshot, late.snapshot)
    }

    @Test
    fun `a child removed by absence is scrubbed from every reference to it`() {
        val shop = ReadModel.of(SHOP_METAMODEL, SHOP_MODEL)
        val shelf = "{\"__id__\": \"shelf\", \"__type__\": \"Shelf\", \"items\": [{\"__id__\": \"i2\", \"__type__\": \"Item\", \"n\": 2}]}"

        val step = shop.reducer().changed(shop.seed, WireTexts.changed("shelf", 3, shelf))

        assertEquals(MirrorChange(setOf("shelf", "clerk"), emptySet(), setOf("i1")), step.change)
        assertEquals(emptyList(), step.snapshot.objects.getValue("clerk").relation("favourite")?.targets)
        assertEquals(3L, step.snapshot.objects.getValue("clerk").revision)
        assertEquals(setOf("shelf", "clerk", "i2"), step.snapshot.containment.keys)
    }

    @Test
    fun `a deletion detaches the object from its owner and scrubs every reference to it`() {
        val step = lamp.reducer().deleted(lamp.seed, WireTexts.deleted("wallSwitch", 9))

        assertEquals(MirrorChange(setOf("living room", "lamp"), emptySet(), setOf("wallSwitch")), step.change)
        assertEquals(emptyList(), step.snapshot.objects.getValue("living room").relation("switch")?.targets)
        assertEquals(emptyList(), step.snapshot.objects.getValue("lamp").relation("control")?.targets)
        assertEquals(9L, step.snapshot.objects.getValue("lamp").revision)
        assertEquals(0L, step.snapshot.objects.getValue("bulb").revision)
        assertTrue("wallSwitch" !in step.snapshot.containment)
    }

    @Test
    fun `a deletion removes the embedded subtree of the object`() {
        val step = lamp.reducer().deleted(lamp.seed, WireTexts.deleted("lamp", 5))

        assertEquals(MirrorChange(setOf("living room"), emptySet(), setOf("lamp", "bulb", "spare")), step.change)
        assertEquals(listOf("living room", "wallSwitch"), step.snapshot.objects.keys.toList())
        assertEquals(mapOf("wallSwitch" to Containment("living room", "switch")), step.snapshot.containment)
    }

    @Test
    fun `deleting an id the mirror does not hold changes nothing`() {
        val step = lamp.reducer().deleted(lamp.seed, WireTexts.deleted("nobody", 5))

        assertSame(lamp.seed, step.snapshot)
        assertEquals(MirrorChange.NONE, step.change)
        assertEquals(emptyList(), step.anomalies)
    }

    @Test
    fun `anonymous objects stay inline and the keyed objects they embed are held by the nearest keyed owner`() {
        val box = ReadModel.of(BOX_METAMODEL, BOX_MODEL)
        val pushed = "{\"__id__\": \"box\", \"__type__\": \"Box\", \"bags\": [{\"__id__\": \"\", \"__type__\": \"Bag\", " +
            "\"inner\": {\"__id__\": \"deep2\", \"__type__\": \"Item\", \"n\": 7}}]}"

        val step = box.reducer().changed(box.seed, WireTexts.changed("box", 2, pushed))
        val bag = step.snapshot.objects.getValue("box").relation("bags")?.targets?.single()

        assertTrue(bag is ObjectRef.Anonymous)
        assertEquals("", bag.value.id)
        assertEquals(ObjectOrigin.RUNTIME, step.snapshot.objects.getValue("deep2").origin)
        assertEquals(ObjectOrigin.MODEL, bag.value.origin)
        assertEquals(listOf(ObjectRef.Keyed("deep2")), bag.value.relation("inner")?.targets)
        assertEquals(Containment("box", "bags"), step.snapshot.containment["deep2"])
        assertEquals(MirrorChange(setOf("box"), setOf("deep2"), setOf("deep")), step.change)
    }

    @Test
    fun `an unknown type keeps its fields by their JSON shape, never as containment, and is reported`() {
        val ghost = "{\"__id__\": \"ghost\", \"__type__\": \"Ghost\", \"glow\": 3, \"tags\": [\"a\", \"b\"], " +
            "\"parts\": [{\"__id__\": \"p1\", \"__type__\": \"Part\"}, null], \"owner\": null}"

        val step = lamp.reducer().changed(lamp.seed, WireTexts.changed("ghost", 3, ghost))
        val obj = step.snapshot.objects.getValue("ghost")

        assertEquals("Ghost", obj.typeName)
        assertEquals(ObjectOrigin.RUNTIME, obj.origin)
        assertEquals(
            listOf(
                MirrorProperty("glow", PropertyValue.Single(ScalarValue.Number(3.0))),
                MirrorProperty("tags", PropertyValue.Many(listOf(ScalarValue.Text("a"), ScalarValue.Text("b")))),
            ),
            obj.properties,
        )
        assertEquals(
            listOf(
                MirrorRelation("parts", null, true, listOf(ObjectRef.Keyed("p1"), ObjectRef.Missing)),
                MirrorRelation("owner", null, false, emptyList()),
            ),
            obj.relations,
        )
        assertTrue("p1" !in step.snapshot.containment)
        assertEquals(setOf("ghost", "p1"), step.change.created)
        assertEquals(3, step.anomalies.size)
        assertTrue(step.anomalies.any { "'Ghost'" in it })
        assertTrue(step.anomalies.any { "'Part'" in it })
        assertTrue(step.anomalies.any { "not in the mirror" in it })
    }

    @Test
    fun `a key the type does not declare and a value of another kind are kept and reported`() {
        val pushed = "{\"__id__\": \"bulb\", \"__type__\": \"Bulb\", \"watts\": \"bright\", \"lit\": false, \"colour\": \"red\"}"

        val step = lamp.reducer().changed(lamp.seed, WireTexts.changed("bulb", 2, pushed))
        val bulb = step.snapshot.objects.getValue("bulb")

        assertEquals(PropertyValue.Single(ScalarValue.Text("bright")), bulb.property("watts")?.value)
        assertEquals(PropertyValue.Single(ScalarValue.Text("red")), bulb.property("colour")?.value)
        assertEquals(2, step.anomalies.size)
        assertTrue(step.anomalies.any { "'colour'" in it })
        assertTrue(step.anomalies.any { "'watts'" in it })
    }

    @Test
    fun `a pushed object without an id is ignored and reported`() {
        val step = lamp.reducer().changed(lamp.seed, WireTexts.changed("", 2, "{\"__id__\": \"\", \"__type__\": \"Bulb\", \"watts\": 5}"))

        assertSame(lamp.seed, step.snapshot)
        assertEquals(MirrorChange.NONE, step.change)
        assertEquals(1, step.anomalies.size)
    }

    @Test
    fun `the association of a relation comes from the schema`() {
        val step = lamp.reducer().replace(lamp.wireRoots(), revision = 1)
        val relations = step.snapshot.objects.getValue("lamp").relations.associate { it.key to it.association }

        assertEquals(
            mapOf("bulb" to AssociationType.EMBEDDED, "spare" to AssociationType.EMBEDDED, "control" to AssociationType.LINK),
            relations,
        )
    }

    /** The runtime's JSON of the crate number [n] the runtime created, labelled [label]. */
    private fun crate(n: Int, label: String): String = "{\"__id__\": \"gen_Crate_$n\", \"__type__\": \"Crate\", \"label\": \"$label\"}"

    /** The runtime's JSON of the depot with [status], the crate in its [slot] and the crates on its [stack]. */
    private fun depotJson(status: String, slot: String, vararg stack: String): String =
        "{\"__id__\": \"depot\", \"__type__\": \"Depot\", \"status\": \"$status\", \"slot\": $slot, \"stack\": [${stack.joinToString(", ")}]}"

    /** The runtime's JSON of the lamp of the synthesized model, knowing [control]. */
    private fun lampJson(control: String): String =
        "{\"__id__\": \"lamp\", \"__type__\": \"Lamp\", \"mode\": \"off\", \"bulb\": $BULB, " +
            "\"spare\": {\"__id__\": \"spare\", \"__type__\": \"Bulb\", \"watts\": 60, \"lit\": false}, \"control\": $control}"

    private companion object {
        /** The runtime's JSON of the lamp's bulb as the model declares it. */
        const val BULB = "{\"__id__\": \"bulb\", \"__type__\": \"Bulb\", \"watts\": 40, \"lit\": false}"

        /** A shop whose clerk knows an item on the shelf. */
        const val SHOP_METAMODEL = """type "Item" "" {
    prop("n", "number")
}
type "Shelf" "" {
    has("items", list("Item"))
}
type "Clerk" "" {
    knows("favourite", "Item")
}
type "Shop" "" {
    has("shelf", "Shelf")
    has("clerk", "Clerk")
}
"""

        /** The shop with two items, the first of them the clerk's favourite. */
        const val SHOP_MODEL = """make object "shop":"Shop" {
    has("shelf") -> make object "shelf":"Shelf" {
        has("items") -> make object "i1":"Item" {
            prop("n") -> "1"
        }
        has("items") -> make object "i2":"Item" {
            prop("n") -> "2"
        }
    }
    has("clerk") -> make object "clerk":"Clerk" {
        knows("favourite") -> "i1"
    }
}
"""

        /** A box of bags, each bag an anonymous object holding a keyed item. */
        const val BOX_METAMODEL = """type "Item" "" {
    prop("n", "number")
}
type "Bag" "" {
    has("inner", "Item")
}
type "Box" "" {
    has("bags", list("Bag"))
}
"""

        /** The box with one anonymous bag. */
        const val BOX_MODEL = """make object "box":"Box" {
    has("bags") -> make object "":"Bag" {
        has("inner") -> make object "deep":"Item" {
            prop("n") -> "1"
        }
    }
}
"""
    }
}
