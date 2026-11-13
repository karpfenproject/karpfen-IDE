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

import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.session.examples.AnalyzedContext
import io.karpfen.ide.runtime.session.examples.AnalyzedContexts
import io.karpfen.ide.runtime.session.examples.WireTexts
import io.karpfen.ide.runtime.session.fixtures.ModelWireText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the round trip from an analyzed model through the runtime's JSON back into the mirror. What the runtime would
 * write of a deployed model reads back as exactly its seed. This holds for the runtime's examples and the synthesized
 * models.
 */
class MirrorRoundTripTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the runtime's JSON of every example and synthesized model reads back as its seed`() = runTest {
        val contexts = AnalyzedContexts.runtimeExamples(this, root.resolve("examples")) +
            AnalyzedContexts.allSynthesized(this, root.resolve("synthesized")).filter { it.name != RING }

        assertEquals(8, contexts.size)
        contexts.forEach { context -> assertRoundTrip(context) }
    }

    @Test
    fun `the runtime refuses to write the ring, whose nodes know each other`() = runTest {
        val ring = AnalyzedContexts.allSynthesized(this, root).single { it.name == RING }
        val wire = ModelWireText(ring.model)

        val refusal = assertFailsWith<IllegalStateException> { wire.rootsJson() }

        assertEquals(RuntimeWireWriter.referenceLoopMessage("a"), refusal.message)
    }

    /** Checks that the snapshot roots and every single object of [context] read back as its seed. */
    private fun assertRoundTrip(context: AnalyzedContext) {
        val seed = context.seed
        val wire = ModelWireText(context.model)
        val reducer = MirrorReducer(context.schema, seed.objects.keys)

        val replaced = reducer.replace(wire.rootsJson().map(WireTexts::wireObject), revision = 0)

        assertEquals(seed, replaced.snapshot, "the snapshot of $context")
        assertEquals(emptyList(), replaced.anomalies, "anomalies of $context")
        seed.objects.keys.forEach { id ->
            val step = reducer.changed(seed, WireTexts.changed(id, 1, wire.objectJson(id)))
            assertEquals(MirrorChange.NONE, step.change, "the push of '$id' of $context")
            assertEquals(seed.objects.keys.toList(), step.snapshot.objects.keys.toList(), "the objects after the push of '$id' of $context")
        }
    }

    private companion object {
        /** The folder of the synthesized model with a reference loop. */
        const val RING = "ring"
    }
}
