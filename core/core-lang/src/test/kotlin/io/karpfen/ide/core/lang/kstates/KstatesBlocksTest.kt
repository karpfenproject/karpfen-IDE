package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.caret
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers the KStates reading of block headers: the introducing keyword, WITH constructs and the enclosing macro. */
class KstatesBlocksTest {

    private fun blocksAt(textWithCaret: String) = caret(textWithCaret).let { (text, offset) ->
        CaretContextDetector().detect(ExampleProject.document(Path.of("/ws/m.kstates"), text), offset).blocks
    }

    @Test
    fun `the introducing keyword is the last one outside parentheses, after earlier actions of the block`() {
        val blocks = blocksAt(
            "DO { SET(\"a\", \"b\") APPEND(\"l\", \"x\") IF IN SCOPE(\"p\") { WITH MACRO(\"m\", \"q\") AS \"x\" { ‸ } } }",
        )

        assertEquals(listOf("DO", "IF", "WITH"), blocks.map(KstatesBlocks::kindOf))
        assertEquals(listOf("IF", "IN", "SCOPE", "(", "p", ")"), KstatesBlocks.constructOf(blocks[1]).map { it.text })
    }

    @Test
    fun `MACRO introduces a block as a definition, not as the call of a WITH`() {
        val blocks = blocksAt("MACROS { MACRO \"m\" { TAKES(\"p\", \"T\") RETURNS(\"number\") DEFINITION { EVAL { ‸ } } } }")

        assertEquals(listOf("MACROS", "MACRO", "DEFINITION", "EVAL"), blocks.map(KstatesBlocks::kindOf))
        assertEquals("m", KstatesBlocks.macroOf(blocks))
        assertNull(KstatesBlocks.macroOf(blocksAt("DO { WITH MACRO(\"m\") AS \"x\" { ‸ } }")))
    }

    @Test
    fun `WITH constructs name their macro and binding`() {
        val with = KstatesBlocks.constructOf(blocksAt("ENTRY { with MACRO(\"step\", \"a\", \"b\") as \"p\" { ‸ } }").last())

        assertEquals("step", KstatesBlocks.boundMacroOf(with))
        assertEquals("p", KstatesBlocks.bindingNameOf(with)?.text)
        assertNull(KstatesBlocks.bindingNameOf(with.dropLast(1)), "no name yet")
        assertEquals(with, KstatesBlocks.withConstructOf(listOf(blocksAt("ENTRY { ‸ }").single().header.first()) + with))
    }

    @Test
    fun `blocks without introducing keyword have no kind`() {
        assertNull(KstatesBlocks.kindOf(blocksAt("{ ‸ }").single()))
        assertEquals(emptyList(), KstatesBlocks.withConstructOf(emptyList()))
    }
}
