package io.karpfen.ide.core.lang.modelpath

import io.karpfen.ide.core.lang.symbols.MemberInfo
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.lang.symbols.TypeOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model-path resolution over `TypeInfo` members (spec §5): roots, bindings, members, lists, events. */
class ModelPathResolverTest {

    private fun member(name: String, kind: MemberKind, type: String, isList: Boolean = false) =
        MemberInfo(name, kind, type, isList, declaration = null)

    private fun type(name: String, vararg members: MemberInfo, origin: TypeOrigin = TypeOrigin.DOMAIN) =
        TypeInfo(name, description = null, members.toList(), origin, declaration = null)

    private val vector = type("Vector", member("x", MemberKind.PROPERTY, "number"), member("y", MemberKind.PROPERTY, "number"))
    private val box = type("TwoDObject", member("diameter", MemberKind.PROPERTY, "number"), member("position", MemberKind.HAS, "Vector"))
    private val obstacle = type("Obstacle", member("boundingBox", MemberKind.HAS, "TwoDObject"))
    private val robot = type(
        "Robot",
        member("log", MemberKind.PROPERTY, "string", isList = true),
        member("boundingBox", MemberKind.HAS, "TwoDObject"),
        member("obstacles", MemberKind.KNOWS, "Obstacle", isList = true),
    )
    private val setSpeed = type("setSpeed", member("factor", MemberKind.PROPERTY, "number"), origin = TypeOrigin.EVENTS)
    private val teleport = type("teleport", member("position", MemberKind.HAS, "Vector"), origin = TypeOrigin.EVENTS)
    private val types = listOf(vector, box, obstacle, robot, setSpeed, teleport)
    private val scope = PathScope(
        types,
        rootType = "Robot",
        bindings = mapOf("dir" to PathType("Vector", isList = false, isPrimitive = false)),
    )
    private val resolver = ModelPathResolver()

    @Test
    fun `paths resolve segment by segment over the members of the root type`() {
        val resolution = resolver.resolve("boundingBox->position->x", scope)

        assertEquals(PathType("number", isList = false, isPrimitive = true), resolution.type)
        assertEquals(listOf("boundingBox", "position", "x"), resolution.segments.map { it.name })
        assertEquals(listOf("Robot", "TwoDObject", "Vector"), resolution.segments.map { it.owner?.name })
        assertTrue(resolution.segments.all { it.member?.origin == PathOrigin.MEMBER })
    }

    @Test
    fun `blanks are ignored and lists continue with their element type`() {
        assertEquals("TwoDObject", resolver.resolve(" obstacles -> boundingBox ", scope).type?.name)
        assertEquals(PathType("string", isList = true, isPrimitive = true), resolver.resolve("log", scope).type)
    }

    @Test
    fun `bindings come first, then self, then event, then the root type's members`() {
        val shadowing = scope.copy(bindings = mapOf("boundingBox" to PathType("Vector", isList = false, isPrimitive = false)))
        assertEquals(PathOrigin.BINDING, resolver.resolve("boundingBox", shadowing).segments.single().member?.origin)
        assertEquals("Vector", resolver.resolve("boundingBox->x", shadowing).segments.last().owner?.name)

        val self = resolver.resolve("self->boundingBox", scope)
        assertEquals(PathOrigin.ROOT, self.segments.first().member?.origin)
        assertEquals("TwoDObject", self.type?.name)
        assertEquals("number", resolver.resolve("dir->y", scope).type?.name)
    }

    @Test
    fun `event resolves against the determined event type, else against the union of all event types`() {
        val determined = scope.copy(eventType = "setSpeed")
        assertEquals("number", resolver.resolve("event->factor", determined).type?.name)
        assertNull(resolver.resolve("event->position", determined).type)

        val union = resolver.resolve("event->position->x", scope)
        assertEquals("number", union.type?.name)
        assertEquals("teleport", union.segments[1].owner?.name)
        assertEquals(listOf("factor", "position"), resolver.completions("event->", scope).map { it.name })
    }

    @Test
    fun `unresolved segments make the rest unresolved and primitives end the path`() {
        val unknown = resolver.resolve("boundingBox->nothing->x", scope)
        assertNull(unknown.type)
        assertEquals(listOf("boundingBox", "nothing", "x"), unknown.segments.map { it.name })
        assertEquals(listOf(true, false, false), unknown.segments.map { it.member != null })

        assertNull(resolver.resolve("log->x", scope).type)
        assertTrue(resolver.resolve("", scope).segments.isEmpty())
        assertNull(resolver.resolve("a->", scope).type)
        assertNull(resolver.resolve("x", scope.copy(rootType = null)).type)
    }

    @Test
    fun `completions of a first segment are bindings, self, event and the root type's members`() {
        assertEquals(
            listOf("dir", "self", "event", "log", "boundingBox", "obstacles"),
            resolver.completions("bou", scope).map { it.name },
        )
        assertEquals(
            listOf(PathOrigin.BINDING, PathOrigin.ROOT, PathOrigin.ROOT),
            resolver.completions("", scope).take(3).map { it.origin },
        )
        val withoutEvents = PathScope(listOf(robot), "Robot")
        assertEquals(listOf("self", "log", "boundingBox", "obstacles"), resolver.completions("", withoutEvents).map { it.name })
    }

    @Test
    fun `completions after complete segments are the members of the reached type`() {
        assertEquals(listOf("diameter", "position"), resolver.completions("boundingBox->", scope).map { it.name })
        assertEquals(listOf("x", "y"), resolver.completions("boundingBox -> position -> x", scope).map { it.name })
        assertEquals(MemberKind.HAS, resolver.completions("obstacles->", scope).single().kind)
        assertTrue(resolver.completions("log->", scope).isEmpty(), "primitive")
        assertTrue(resolver.completions("nothing->", scope).isEmpty(), "unresolved")
    }
}
