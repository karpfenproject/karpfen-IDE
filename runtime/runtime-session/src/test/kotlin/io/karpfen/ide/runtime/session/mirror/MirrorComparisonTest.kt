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

/** Covers the comparison of two snapshots object by object, as the deploy compares the runtime's read-back with the seed. */
class MirrorComparisonTest {

    private val lamp = ReadModel.synthesized(SynthesizedModel.LAMP)

    @Test
    fun `a read-back of the deployed model does not differ from the seed, whatever its revision and origins`() {
        val readBack = MirrorReducer(lamp.schema, emptySet()).replace(lamp.wireRoots(), revision = 40).snapshot

        assertEquals(emptyList(), MirrorComparison.differences(lamp.seed, readBack))
    }

    @Test
    fun `differences follow the order of the expected snapshot, then come the objects only the actual one has`() {
        val reducer = lamp.reducer()
        val withoutSpare = reducer.deleted(lamp.seed, WireTexts.deleted("spare", 1)).snapshot
        val changed = reducer.changed(
            withoutSpare,
            WireTexts.changed("bulb", 2, "{\"__id__\": \"bulb\", \"__type__\": \"Bulb\", \"watts\": 100, \"lit\": true}"),
        ).snapshot
        val actual = reducer.changed(
            changed,
            WireTexts.changed("ghost", 3, "{\"__id__\": \"ghost\", \"__type__\": \"Ghost\"}"),
        ).snapshot

        val differences = MirrorComparison.differences(lamp.seed, actual)

        assertEquals(
            listOf(
                ObjectDifference("lamp", DifferenceKind.DIFFERENT, "In 'lamp', the relation 'spare' holds [] instead of ['spare']."),
                ObjectDifference(
                    "bulb",
                    DifferenceKind.DIFFERENT,
                    "In 'bulb', the property 'watts' is 100.0 instead of 40.0; the property 'lit' is true instead of false.",
                ),
                ObjectDifference("spare", DifferenceKind.MISSING, "The object 'spare' of type 'Bulb' is missing."),
                ObjectDifference("ghost", DifferenceKind.UNEXPECTED, "The object 'ghost' of type 'Ghost' is not expected."),
            ),
            differences,
        )
    }

    @Test
    fun `a different type, a missing or unexpected member and anonymous targets by content are told apart`() {
        val anonymous = MirrorObject("", "Bag", listOf(MirrorProperty("n", PropertyValue.Single(ScalarValue.Number(1.0)))), emptyList(), ObjectOrigin.MODEL, 0)
        val expected = snapshotOf(
            MirrorObject(
                "x",
                "Box",
                listOf(MirrorProperty("label", PropertyValue.Single(ScalarValue.Text("a")))),
                listOf(MirrorRelation("bags", AssociationType.EMBEDDED, true, listOf(ObjectRef.Anonymous(anonymous)))),
                ObjectOrigin.MODEL,
                0,
            ),
        )
        val sameContent = snapshotOf(
            expected.objects.getValue("x").copy(
                origin = ObjectOrigin.RUNTIME,
                revision = 9,
                relations = listOf(
                    MirrorRelation(
                        "bags",
                        AssociationType.EMBEDDED,
                        true,
                        listOf(ObjectRef.Anonymous(anonymous.copy(origin = ObjectOrigin.RUNTIME, revision = 9))),
                    ),
                ),
            ),
        )
        val different = snapshotOf(
            MirrorObject(
                "x",
                "Crate",
                listOf(MirrorProperty("weight", PropertyValue.Many(listOf(ScalarValue.Number(1.0), ScalarValue.Flag(true))))),
                listOf(MirrorRelation("bags", AssociationType.EMBEDDED, true, listOf(ObjectRef.Anonymous(anonymous.copy(typeName = "Sack")), ObjectRef.Missing))),
                ObjectOrigin.MODEL,
                0,
            ),
        )

        assertEquals(emptyList(), MirrorComparison.differences(expected, sameContent))
        assertEquals(
            listOf(
                ObjectDifference(
                    "x",
                    DifferenceKind.DIFFERENT,
                    "In 'x', its type is 'Crate' instead of 'Box'; the property 'label' is missing; " +
                        "the property 'weight' is not expected; the relation 'bags' holds [an anonymous 'Sack', nothing] " +
                        "instead of [an anonymous 'Bag'].",
                ),
            ),
            MirrorComparison.differences(expected, different),
        )
    }

    /** A snapshot holding only [objects], each a root. */
    private fun snapshotOf(vararg objects: MirrorObject): MirrorSnapshot =
        MirrorSnapshot(objects.associateBy { it.id }, objects.map { ObjectRef.Keyed(it.id) }, emptyMap())
}
