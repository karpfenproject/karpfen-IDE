package io.karpfen.ide.core.workspace.files

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers [FileKind.of], the role of a file derived from its name only, and which files fit a descriptor role. */
class FileKindTest {

    @Test
    fun `karpfen sources map to their languages`() {
        assertEquals(FileKind.KMETA, FileKind.of("robot.kmeta"))
        assertEquals(FileKind.KMETA, FileKind.of("Robot.KMETA"))
        assertEquals(FileKind.KMODEL, FileKind.of("robot.kmodel"))
        assertEquals(FileKind.KSTATES, FileKind.of("robot.KStates"))
        assertEquals(KarpfenLanguage.KSTATES, FileKind.KSTATES.language)
        assertEquals(KarpfenLanguage.KMETA, FileKind.EVENTS_KMETA.language)
    }

    @Test
    fun `the event metamodel is EVENTS kmeta in any case, and only that name`() {
        assertEquals(FileKind.EVENTS_KMETA, FileKind.of("EVENTS.kmeta"))
        assertEquals(FileKind.EVENTS_KMETA, FileKind.of("events.kmeta"))
        assertEquals(FileKind.KMETA, FileKind.of("MY_EVENTS.kmeta"))
        assertEquals(FileKind.KMETA, FileKind.of("EVENTS.kmeta.kmeta"))
    }

    @Test
    fun `descriptors are edited in the environment language but are no karpfen source of a context`() {
        assertEquals(FileKind.ENV_DESCRIPTOR, FileKind.of("robot.kenv.json"))
        assertEquals(FileKind.ENV_DESCRIPTOR, FileKind.of("robot.KENV.JSON"))
        assertEquals(KarpfenLanguage.ENVIRONMENT, FileKind.ENV_DESCRIPTOR.language)
        assertFalse(FileKind.ENV_DESCRIPTOR.isKarpfenSource)
        assertTrue(FileKind.KMODEL.isKarpfenSource)
    }

    @Test
    fun `bridges, layouts and other files have no language`() {
        assertEquals(FileKind.BRIDGE, FileKind.of("mqtt-sim.kbridge.json"))
        assertEquals(FileKind.LAYOUT, FileKind.of("robot.kstates.layout.json"))
        assertEquals(FileKind.OTHER, FileKind.of("robot.json"))
        assertEquals(FileKind.OTHER, FileKind.of("kmeta"))
        assertEquals(FileKind.OTHER, FileKind.of("robot.kmeta.bak"))
        assertEquals(FileKind.OTHER, FileKind.of(""))
        listOf(FileKind.BRIDGE, FileKind.LAYOUT, FileKind.OTHER).forEach {
            assertNull(it.language)
            assertFalse(it.isKarpfenSource)
        }
    }

    @Test
    fun `a file fits a descriptor role by its language, and any file fits a role without one`() {
        assertEquals(
            mapOf(
                DescriptorFileRole.METAMODEL to listOf(FileKind.KMETA, FileKind.EVENTS_KMETA),
                DescriptorFileRole.EVENTS to listOf(FileKind.KMETA, FileKind.EVENTS_KMETA),
                DescriptorFileRole.MODEL to listOf(FileKind.KMODEL),
                DescriptorFileRole.STATE_MACHINE to listOf(FileKind.KSTATES),
                DescriptorFileRole.BRIDGE to FileKind.entries,
            ),
            DescriptorFileRole.entries.associateWith { role -> FileKind.entries.filter { it.fits(role) } },
        )
    }

    @Test
    fun `the usual file of a role is one that fits it, EVENTS kmeta for the events and the others for the metamodel`() {
        assertEquals(
            mapOf(
                DescriptorFileRole.METAMODEL to listOf(FileKind.KMETA),
                DescriptorFileRole.EVENTS to listOf(FileKind.EVENTS_KMETA),
                DescriptorFileRole.MODEL to listOf(FileKind.KMODEL),
                DescriptorFileRole.STATE_MACHINE to listOf(FileKind.KSTATES),
                DescriptorFileRole.BRIDGE to listOf(FileKind.BRIDGE),
            ),
            DescriptorFileRole.entries.associateWith { role -> FileKind.entries.filter { it.isUsualFor(role) } },
        )
        assertTrue(FileKind.entries.all { kind -> DescriptorFileRole.entries.all { !kind.isUsualFor(it) || kind.fits(it) } })
    }
}
