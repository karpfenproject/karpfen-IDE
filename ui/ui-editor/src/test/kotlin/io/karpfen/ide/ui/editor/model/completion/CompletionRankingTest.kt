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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Filtering and ranking: prefix > camel/word-boundary > subsequence, stable by sortKey and label. */
class CompletionRankingTest {

    private fun item(label: String, sortKey: String = label, detail: String? = null) =
        CompletionItem(label, CompletionKind.KEYWORD, label, TextRange.EMPTY, null, detail, null, sortKey)

    private fun ranked(filter: String, vararg labels: String): List<String> =
        CompletionRanking.rank(labels.map { item(it) }, filter).map { it.label }

    @Test
    fun `match qualities`() {
        assertEquals(MatchQuality.PREFIX, CompletionRanking.qualityOf("position", "pos"))
        assertEquals(MatchQuality.PREFIX, CompletionRanking.qualityOf("Position", "pOS"), "case-insensitive")
        assertEquals(MatchQuality.PREFIX, CompletionRanking.qualityOf("anything", ""))
        assertEquals(MatchQuality.WORD_BOUNDARY, CompletionRanking.qualityOf("simpleProperty", "sP"))
        assertEquals(MatchQuality.WORD_BOUNDARY, CompletionRanking.qualityOf("simpleProperty", "simprop"))
        assertEquals(MatchQuality.WORD_BOUNDARY, CompletionRanking.qualityOf("robot->position", "pos"))
        assertEquals(MatchQuality.WORD_BOUNDARY, CompletionRanking.qualityOf("max_speed", "ms"))
        assertEquals(MatchQuality.WORD_BOUNDARY, CompletionRanking.qualityOf("INITIAL STATE", "is"))
        assertEquals(MatchQuality.WORD_BOUNDARY, CompletionRanking.qualityOf("sensor2", "s2"))
        assertEquals(MatchQuality.SUBSEQUENCE, CompletionRanking.qualityOf("simpleProperty", "spy"))
        assertEquals(MatchQuality.SUBSEQUENCE, CompletionRanking.qualityOf("position", "ptn"))
        assertNull(CompletionRanking.qualityOf("position", "x"))
        assertNull(CompletionRanking.qualityOf("pos", "position"))
    }

    @Test
    fun `prefix matches come before word-boundary matches before subsequences`() {
        assertEquals(
            listOf("speed", "sensorPosition", "suspend"),
            ranked("sp", "suspend", "sensorPosition", "speed", "battery"),
        )
    }

    @Test
    fun `equally good items are ordered by sort key, then label`() {
        val items = listOf(item("b", sortKey = "2"), item("a", sortKey = "2"), item("c", sortKey = "1"))
        assertEquals(listOf("c", "a", "b"), CompletionRanking.rank(items, "").map { it.label })
    }

    @Test
    fun `the sort is stable for items with the same key and label`() {
        val items = (0 until 5).map { item("same", sortKey = "k", detail = "#$it") }
        assertEquals(items.map { it.detail }, CompletionRanking.rank(items, "s").map { it.detail })
    }

    @Test
    fun `ranking never reorders equally good items as the filter grows`() {
        val random = Random(42)
        val labels = (0 until 200).map { String(CharArray(random.nextInt(2, 10)) { "abcAB_".random(random) }) }
        val items = labels.mapIndexed { index, label -> item(label, sortKey = "%03d".format(index % 7)) }
        for (filter in listOf("", "a", "ab", "aB", "a_b")) {
            val result = CompletionRanking.rank(items, filter)
            val keys = result.map { CompletionRanking.qualityOf(it.label, filter)!! to it.sortKey }
            assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys, "sorted for '$filter'")
            assertTrue(result.all { CompletionRanking.qualityOf(it.label, filter) != null })
            assertEquals(items.count { CompletionRanking.qualityOf(it.label, filter) != null }, result.size)
        }
    }
}
