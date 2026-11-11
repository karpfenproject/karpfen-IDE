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

import io.karpfen.dsl.instance.ClassTypeListPropertyObject
import io.karpfen.dsl.instance.ObjectReference
import io.karpfen.dsl.meta.AssociationType
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.runtime.session.examples.AnalyzedContext
import io.karpfen.ide.runtime.session.examples.AnalyzedContexts
import io.karpfen.ide.runtime.session.examples.DslTexts
import io.karpfen.ide.runtime.session.schema.TypeSchema
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the seed, which is the analyzed model as the mirror's first snapshot. It checks the runtime's four examples,
 * the synthesized models and hand-written models with anonymous objects and unresolved references.
 */
class MirrorSeederTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the single robot's seed holds every keyed object once with the members the model writes`() = runTest {
        val seed = AnalyzedContexts.runtimeExamples(this, root).first().seed
        val turtle = seed.objects.getValue("turtle")

        assertEquals(listOf(ObjectRef.Keyed("APB 2101")), seed.roots)
        assertEquals("Robot", turtle.typeName)
        assertEquals(
            listOf(
                MirrorProperty("d_closest_obstacle", PropertyValue.Single(ScalarValue.Number(100.0))),
                MirrorProperty("d_closest_wall", PropertyValue.Single(ScalarValue.Number(100.0))),
            ),
            turtle.properties,
        )
        assertEquals(
            listOf("boundingBox", "direction", "obstacles", "walls", "closest_obstacle", "closest_wall"),
            turtle.relations.map { it.key },
        )
        assertEquals(
            MirrorRelation("boundingBox", AssociationType.EMBEDDED, false, listOf(ObjectRef.Keyed("turtleBoundingBox"))),
            turtle.relation("boundingBox"),
        )
        assertEquals(
            MirrorRelation("obstacles", AssociationType.LINK, true, listOf(ObjectRef.Keyed("chair"), ObjectRef.Keyed("table"))),
            turtle.relation("obstacles"),
        )
        assertEquals(
            MirrorRelation("closest_wall", AssociationType.LINK, false, listOf(ObjectRef.Keyed("wall_top"))),
            turtle.relation("closest_wall"),
        )
        assertEquals(
            listOf(MirrorProperty("x", PropertyValue.Single(ScalarValue.Number(5.0))), MirrorProperty("y", PropertyValue.Single(ScalarValue.Number(5.0)))),
            seed.objects.getValue("turtlePosition").properties,
        )
        assertEquals(Containment("APB 2101", "robot"), seed.containment["turtle"])
        assertEquals(Containment("turtleBoundingBox", "position"), seed.containment["turtlePosition"])
        assertEquals(Containment("APB 2101", "obstacles"), seed.containment["chair"])
        assertNull(seed.containment["APB 2101"])
    }

    @Test
    fun `every example and synthesized model seeds each keyed object once, from the model, at revision 0`() = runTest {
        val contexts = AnalyzedContexts.runtimeExamples(this, root.resolve("examples")) +
            AnalyzedContexts.allSynthesized(this, root.resolve("synthesized"))

        assertEquals(8, contexts.size)
        contexts.forEach { context -> assertSeedOf(context) }
    }

    @Test
    fun `relations come in the reader's order, has before knows, whatever order the text writes them in`() {
        val metamodel = DslTexts.metamodel(
            """
            type "Vector" "" {
                prop("x", "number")
            }
            type "Robot" "" {
                prop("name", "string")
                prop("busy", "boolean")
                knows("buddy", "Robot")
                has("position", "Vector")
            }
            """.trimIndent(),
        )
        val model = DslTexts.model(
            """
            make object "r":"Robot" {
                knows("buddy") -> "r"
                prop("busy") -> "true"
                has("position") -> make object "p":"Vector" {
                    prop("x") -> "1"
                }
            }
            """.trimIndent(),
            metamodel,
        )
        val robot = MirrorSeeder.seed(model, TypeSchema.of(metamodel)).objects.getValue("r")

        assertEquals(listOf("position", "buddy"), robot.relations.map { it.key })
        assertEquals(listOf(MirrorProperty("busy", PropertyValue.Single(ScalarValue.Flag(true)))), robot.properties)
    }

    @Test
    fun `anonymous objects live inline, an unresolved single reference is empty and an unresolved list entry is missing`() {
        val metamodel = DslTexts.metamodel(
            """
            type "Item" "" {
                prop("n", "number")
                prop("tags", list("string"))
            }
            type "Bag" "" {
                has("inner", "Item")
            }
            type "Box" "" {
                has("bags", list("Bag"))
                has("lid", "Item")
                knows("picks", list("Item"))
                knows("favourite", "Item")
            }
            """.trimIndent(),
        )
        val model = DslTexts.partialModel(
            """
            make object "box":"Box" {
                has("bags") -> make object "":"Bag" {
                    has("inner") -> make object "deep":"Item" {
                        prop("n") -> "1"
                    }
                }
                has("lid") -> make object "lid":"Item" {
                    prop("n") -> "2"
                    prop("tags") -> "a"
                    prop("tags") -> "b"
                }
                knows("picks") -> "deep"
                knows("picks") -> "nowhere"
                knows("favourite") -> "nowhere"
            }
            """.trimIndent(),
            metamodel,
        )
        val boxObject = model.objects.single()
        val picks = boxObject.relations.single { it.key == "picks" } as ClassTypeListPropertyObject
        boxObject.relations[boxObject.relations.indexOf(picks)] =
            ClassTypeListPropertyObject(picks.ofType, picks.key, (picks.getValueRefs() + ObjectReference("elsewhere")).toMutableList())
        val seed = MirrorSeeder.seed(model, TypeSchema.of(metamodel))
        val box = seed.objects.getValue("box")
        val bag = MirrorObject(
            id = "",
            typeName = "Bag",
            properties = emptyList(),
            relations = listOf(MirrorRelation("inner", AssociationType.EMBEDDED, false, listOf(ObjectRef.Keyed("deep")))),
            origin = ObjectOrigin.MODEL,
            revision = 0,
        )

        assertEquals(listOf("box", "deep", "lid"), seed.objects.keys.toList())
        assertEquals(listOf(ObjectRef.Anonymous(bag)), box.relation("bags")?.targets)
        assertEquals(listOf(ObjectRef.Keyed("deep"), ObjectRef.Missing), box.relation("picks")?.targets)
        assertEquals(emptyList(), box.relation("favourite")?.targets)
        assertEquals(Containment("box", "bags"), seed.containment["deep"])
        assertEquals(Containment("box", "lid"), seed.containment["lid"])
        assertEquals(
            MirrorProperty("tags", PropertyValue.Many(listOf(ScalarValue.Text("a"), ScalarValue.Text("b")))),
            seed.objects.getValue("lid").property("tags"),
        )
    }

    /** Checks the seed of [context] against the objects its model declares. */
    private fun assertSeedOf(context: AnalyzedContext) {
        val seed = context.seed
        val declared = ContextSymbols(context.analysis).objectsOf(context.context.context.models.single()).map { it.key }
        assertEquals(declared.distinct(), seed.objects.keys.toList(), "the keyed objects of $context in document order")
        assertTrue(seed.objects.values.all { it.origin == ObjectOrigin.MODEL && it.revision == 0L }, "origin and revision of $context")
        seed.objects.forEach { (id, obj) -> assertEquals(id, obj.id) }
        val referenced = seed.objects.values.flatMap { obj -> obj.relations.flatMap { it.targets } }.filterIsInstance<ObjectRef.Keyed>()
        assertTrue(referenced.all { it.id in seed.objects }, "every reference of $context is to a keyed object of the seed")
        val embedded = seed.objects.values.flatMap { obj ->
            obj.relations.filter { it.association == AssociationType.EMBEDDED }.flatMap { relation ->
                relation.targets.filterIsInstance<ObjectRef.Keyed>().map { it.id to Containment(obj.id, relation.key) }
            }
        }.toMap()
        assertEquals(embedded, seed.containment, "the containment of $context")
    }
}
