package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Unit tests of the session's collaborators that hold no coroutines: the external store and the merger. */
class SessionCollaboratorsTest {

    @Test
    fun `the external store keeps a copy per source and removes a source on an empty list`() {
        val text = EditableText("abc")
        val store = ExternalDiagnosticsStore(text.lineage)
        val list = mutableListOf(diagnostic(TextRange(0, 1)))

        assertTrue(store.set("project", text.snapshot.value, list))
        list.clear()

        assertEquals(listOf(diagnostic(TextRange(0, 1))), store.sets.value.getValue("project").diagnostics)
        assertSame(text.snapshot.value, store.sets.value.getValue("project").snapshot)
        assertTrue(store.set("project", text.snapshot.value, emptyList()))
        assertTrue(store.sets.value.isEmpty())
    }

    @Test
    fun `the external store ignores snapshots of another lineage`() {
        val store = ExternalDiagnosticsStore(TextLineage())

        assertFalse(store.set("project", TextSnapshot("abc", 0), listOf(diagnostic(TextRange(0, 1)))))
        assertTrue(store.sets.value.isEmpty())
    }

    @Test
    fun `concurrent updates of the external store are never lost`() {
        val text = EditableText("abc")
        val store = ExternalDiagnosticsStore(text.lineage)

        List(8) { index ->
            thread { repeat(200) { store.set("source-$index-$it", text.snapshot.value, listOf(diagnostic(TextRange(0, 1)))) } }
        }.forEach { it.join() }

        assertEquals(8 * 200, store.sets.value.size)
    }

    @Test
    fun `the merger maps every source to the newest snapshot and merges with the one rule`() {
        val text = EditableText("0123456789")
        val v0 = text.snapshot.value
        val lexical = diagnostic(TextRange(1, 2), DiagnosticSource.LEXER)
        val parser = diagnostic(TextRange(3, 4))
        val external = diagnostic(TextRange(5, 6), DiagnosticSource.DSL_TOOLS)
        text.type(0, "ab")

        val merged = DiagnosticsMerger(text).merge(
            TokenizeResult(0, emptyList(), listOf(lexical)),
            outcome(0, syntax = listOf(parser, lexical)),
            mapOf("project" to ExternalDiagnosticSet(v0, listOf(external))),
        )

        assertSame(text.snapshot.value, merged.snapshot)
        assertEquals(
            listOf(TextRange(3, 4), TextRange(5, 6), TextRange(7, 8)),
            merged.diagnostics.map { it.range },
        )
        assertEquals(listOf(DiagnosticSource.LEXER, DiagnosticSource.PARSER, DiagnosticSource.DSL_TOOLS), merged.diagnostics.map { it.source })
    }

    @Test
    fun `the merger maps an external set from its previous merge, and a newly reported set from its own snapshot`() {
        val text = EditableText("0123456789", historyLimit = 1)
        val merger = DiagnosticsMerger(text)
        val reported = mapOf("file" to ExternalDiagnosticSet(text.snapshot.value, listOf(diagnostic(TextRange(5, 6)))))
        fun mergedRanges(external: Map<String, ExternalDiagnosticSet>) =
            merger.merge(TokenizeResult.empty(text.snapshot.value.version), null, external).diagnostics.map { it.range }

        text.type(0, "a")
        assertEquals(listOf(TextRange(6, 7)), mergedRanges(reported))
        text.type(0, "b")
        assertEquals(listOf(TextRange(7, 8)), mergedRanges(reported), "version 0 is evicted; mapped from version 1")

        val replaced = mapOf("file" to ExternalDiagnosticSet(text.snapshot.value, listOf(diagnostic(TextRange(0, 1)))))
        assertEquals(listOf(TextRange(0, 1)), mergedRanges(replaced))
    }

    @Test
    fun `the merger keeps diagnostics of the current version unchanged`() {
        val text = EditableText("0123456789")
        val parser = diagnostic(TextRange(3, 4))

        val merged = DiagnosticsMerger(text).merge(TokenizeResult.empty(0), outcome(0, syntax = listOf(parser)), emptyMap())

        assertSame(parser, merged.diagnostics.single())
    }
}
