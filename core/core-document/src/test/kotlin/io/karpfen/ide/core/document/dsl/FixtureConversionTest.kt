package io.karpfen.ide.core.document.dsl

import dsl.textual.KmetaDSLConverter
import dsl.textual.KmodelDSLConverter
import dsl.textual.KstatesDSLConverter
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Baseline for spec §11 item 2: every example context converts with the dsl-tools converters themselves (called
 * directly, which only this adapter package may do), and so does a synthetic KStates document. If this fails, the
 * fixtures or dsl-tools changed, not the IDE.
 */
class FixtureConversionTest {

    @Test
    fun `every example context converts with dsl-tools`() {
        for (context in Examples.contexts()) {
            val metamodel = KmetaDSLConverter.parseKmetaString(context.metamodel!!.text)
            context.events?.let { events -> KmetaDSLConverter.parseKmetaString(events.text, metamodel.types) }
            context.models.forEach { model -> KmodelDSLConverter.parseKmodelString(model.text, metamodel) }
            context.stateMachines.forEach { machine -> KstatesDSLConverter.parseKstatesString(machine.text) }
        }
    }

    @Test
    fun `a synthetic KStates document converts with dsl-tools`() {
        val machine = KstatesDSLConverter.parseKstatesString(SyntheticKstates(120))

        assertEquals(SyntheticKstates.ATTACHED_TYPE, machine.attachedToClass)
        assertEquals(2, machine.macros.size)
    }
}
