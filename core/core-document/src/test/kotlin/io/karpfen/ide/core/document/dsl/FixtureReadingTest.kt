package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Baseline for reading the examples: every example context reads without a finding with the dsl-tools readers
 * themselves (called directly, which only this adapter package may do), and so does a synthetic KStates document. If
 * this fails, the fixtures or dsl-tools changed, not the IDE.
 */
class FixtureReadingTest {

    @Test
    fun `every example context reads without findings with dsl-tools`() {
        for (context in Examples.contexts()) {
            val metamodel = KmetaReader().read(context.metamodel!!.text)
            assertEquals(emptyList(), metamodel.diagnostics, "$context")
            val domain = metamodel.model!!
            val reads = listOfNotNull(context.events?.let { it to KmetaReader(domain.types).read(it.text) }) +
                context.models.map { it to KmodelReader(domain).read(it.text) } +
                context.stateMachines.map { it to KstatesReader().read(it.text) }
            reads.forEach { (file, read) -> assertEquals(emptyList(), read.diagnostics, file.path) }
        }
    }

    @Test
    fun `a synthetic KStates document reads without findings with dsl-tools`() {
        val read = KstatesReader().read(SyntheticKstates(120))

        assertEquals(emptyList(), read.diagnostics)
        assertEquals(SyntheticKstates.ATTACHED_TYPE, read.model!!.attachedToClass)
        assertEquals(2, read.model!!.macros.size)
    }
}
