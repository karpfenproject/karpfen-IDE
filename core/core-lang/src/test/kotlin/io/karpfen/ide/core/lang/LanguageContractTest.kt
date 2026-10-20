package io.karpfen.ide.core.lang

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.lang.services.karpfenLanguageFeatures
import io.karpfen.ide.core.lang.symbols.MemberInfo
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.lang.symbols.TypeOrigin
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract test of the complete core-lang values and wiring (no phase-1 implementation code is executed). */
class LanguageContractTest {

    @Test
    fun `every registered language has feature providers`() {
        val features = karpfenLanguageFeatures()

        assertEquals(defaultLanguageRegistry().languages, features.map { it.language }.toSet())
        assertEquals(features.size, features.map { it.language }.toSet().size, "one bundle per language")
    }

    @Test
    fun `member signatures and type hovers use the spec format`() {
        val position = MemberInfo("position", MemberKind.HAS, "Vector", isList = false, declaration = null)
        val obstacles = MemberInfo("obstacles", MemberKind.KNOWS, "Obstacle", isList = true, declaration = null)
        val x = MemberInfo("x", MemberKind.PROPERTY, "number", isList = false, declaration = null)

        assertEquals("has position: Vector", position.signature)
        assertEquals("knows obstacles: list(Obstacle)", obstacles.signature)
        assertEquals("prop x: number", x.signature)
        assertTrue(x.isPrimitive)
        assertEquals(
            "type Robot\nA cleaning robot\n\nprop x: number\nhas position: Vector",
            TypeInfo("Robot", "A cleaning robot", listOf(x, position), TypeOrigin.DOMAIN, declaration = null).hoverText,
        )
        assertEquals("type Empty", TypeInfo("Empty", "", emptyList(), TypeOrigin.DOCUMENT, declaration = null).hoverText)
    }

    @Test
    fun `documents know whether their tokens belong to their text`() {
        val text = TextSnapshot("STATE", 3)
        val path = Path.of("/ws/robot.kstates").toAbsolutePath()

        assertTrue(LanguageDocument(path, KarpfenLanguage.KSTATES, text, TokenizeResult.empty(3), parse = null).isConsistent)
        val lagging = LanguageDocument(path, KarpfenLanguage.KSTATES, text, TokenizeResult.empty(2), parse = null)
        assertFalse(lagging.isConsistent)
        assertNull(lagging.currentParse)
        assertNull(Location(path, TextRange(0, 5)).version)
        assertFailsWith<IllegalArgumentException> {
            CompletionItem("x", CompletionKind.KEYWORD, "x", TextRange.EMPTY, 2, null, null, "x")
        }
    }
}
