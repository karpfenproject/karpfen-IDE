package io.karpfen.ide.core.workspace.files

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers [FileKind.of], the role of a file derived from its name only. */
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
    fun `every file role of a descriptor has the kind of file completion offers for it`() {
        assertEquals(
            listOf(FileKind.KMETA, FileKind.EVENTS_KMETA, FileKind.KMODEL, FileKind.KSTATES, FileKind.BRIDGE),
            DescriptorFileRole.entries.map { FileKind.of(it) },
        )
        DescriptorFileRole.entries.forEach { role -> assertEquals(role.language, FileKind.of(role).language, "$role") }
    }
}
