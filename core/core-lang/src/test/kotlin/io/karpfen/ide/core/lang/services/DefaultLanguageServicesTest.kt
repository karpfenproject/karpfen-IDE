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
package io.karpfen.ide.core.lang.services

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HighlightProvider
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the language services facade: delegation per language with the project and context analysis, guarded
 * preconditions, and provider failures that are caught, logged and reported.
 */
class DefaultLanguageServicesTest {

    private val span = HighlightSpan(TextRange(0, 1), HighlightStyle.KEYWORD)
    private val item = CompletionItem("x", CompletionKind.KEYWORD, "x", TextRange.EMPTY, null, null, null, "x")
    private val hover = Hover(TextRange(0, 1), "h")
    private val bracket = BracketMatch(TextRange(0, 1), TextRange(1, 2))

    /** Features of [language] answering fixed values, recording the requests, or throwing [error] if set. */
    private class FakeFeatures(
        override val language: KarpfenLanguage,
        private val values: DefaultLanguageServicesTest,
        private val error: Throwable? = null,
    ) : LanguageFeatures, NavigationProvider {
        val requests = ArrayList<FeatureRequest>()

        private fun <T> answer(request: FeatureRequest, value: T): T {
            requests += request
            error?.let { throw it }
            return value
        }

        override val highlight = HighlightProvider { answer(it, listOf(values.span)) }
        override val completion = CompletionProvider { request, _ -> answer(request, listOf(values.item)) }
        override val navigation: NavigationProvider get() = this
        override val hover = HoverProvider { request, _ -> answer(request, values.hover) }
        override val brackets = BracketMatcher { request, _ -> answer(request, values.bracket) }

        override fun definition(request: FeatureRequest, offset: Int): List<Location> =
            answer(request, listOf(Location(request.document.path, TextRange(offset, offset))))

        override fun references(request: FeatureRequest, offset: Int): List<Location> = definition(request, offset).let { it + it }
    }

    private val document = ExampleProject.example(ExamplePaths.ROBOT_KSTATES)

    @Test
    fun `requests go to the features of the document's language with the project and context analysis`() {
        val kstates = FakeFeatures(KarpfenLanguage.KSTATES, this)
        val kmeta = FakeFeatures(KarpfenLanguage.KMETA, this)
        val analysis = MutableStateFlow(ExampleProject.analysis)
        val services = defaultLanguageServices(analysis, listOf(kmeta, kstates))

        assertEquals(listOf(span), services.highlight(document))
        assertEquals(listOf(item), services.complete(document, 3))
        assertEquals(listOf(Location(document.path, TextRange(3, 3))), services.definition(document, 3))
        assertEquals(2, services.references(document, 3).size)
        assertEquals(hover, services.hover(document, 3))
        assertEquals(bracket, services.matchingBracket(document, 3))

        assertTrue(kmeta.requests.isEmpty())
        assertEquals(6, kstates.requests.size)
        val request = kstates.requests.first()
        assertSame(ExampleProject.analysis, request.project)
        assertSame(ExampleProject.analysis.contextOf(document.path), request.contextAnalysis)
        assertSame(document, request.document)

        val next = ProjectAnalysis(2, emptyList(), emptyMap())
        analysis.value = next
        services.hover(document, 0)
        assertSame(next, kstates.requests.last().project, "the latest analysis is read per request")
        assertNull(kstates.requests.last().contextAnalysis)
    }

    @Test
    fun `provider exceptions yield empty results and are reported with operation and language`() {
        val error = IllegalStateException("boom")
        val failures = ArrayList<ProviderFailure>()
        val services = defaultLanguageServices(
            MutableStateFlow(ExampleProject.analysis),
            listOf(FakeFeatures(KarpfenLanguage.KSTATES, this, error)),
            onProviderFailure = { failures += it },
        )

        assertTrue(services.highlight(document).isEmpty())
        assertTrue(services.complete(document, 0).isEmpty())
        assertTrue(services.definition(document, 0).isEmpty())
        assertTrue(services.references(document, 0).isEmpty())
        assertNull(services.hover(document, 0))
        assertNull(services.matchingBracket(document, 0))

        assertEquals(listOf("highlight", "complete", "definition", "references", "hover", "matchingBracket"), failures.map { it.operation })
        assertTrue(failures.all { it.language == KarpfenLanguage.KSTATES && it.error === error })
    }

    @Test
    fun `stack overflows are caught too and a throwing failure callback does not escape`() {
        val services = defaultLanguageServices(
            MutableStateFlow(ExampleProject.analysis),
            listOf(FakeFeatures(KarpfenLanguage.KSTATES, this, StackOverflowError("deep"))),
            onProviderFailure = { throw IllegalArgumentException("callback") },
        )

        assertTrue(services.complete(document, 0).isEmpty())
        assertNull(services.hover(document, 0))
    }

    @Test
    fun `languages without features yield empty results`() {
        val services = defaultLanguageServices(MutableStateFlow(ExampleProject.analysis), emptyList())

        assertTrue(services.highlight(document).isEmpty())
        assertTrue(services.complete(document, 0).isEmpty())
        assertNull(services.matchingBracket(document, 0))
    }

    @Test
    fun `offsets outside the text and lagging tokens are not passed to providers`() {
        val features = FakeFeatures(KarpfenLanguage.KSTATES, this)
        val services = defaultLanguageServices(MutableStateFlow(ExampleProject.analysis), listOf(features))
        val lagging: LanguageDocument = document.copy(tokens = TokenizeResult.empty(document.text.version - 1))

        assertTrue(services.complete(document, -1).isEmpty())
        assertTrue(services.complete(document, document.text.length + 1).isEmpty())
        assertNull(services.hover(lagging, 0))
        assertTrue(services.definition(lagging, 0).isEmpty())
        assertTrue(features.requests.isEmpty())
        assertEquals(listOf(span), services.highlight(lagging), "highlight takes the tokens' version")
        assertEquals(listOf(item), services.complete(document, document.text.length), "the end of the text is a valid offset")
    }

    @Test
    fun `two feature bundles of one language are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            defaultLanguageServices(
                MutableStateFlow(ProjectAnalysis.EMPTY),
                listOf(FakeFeatures(KarpfenLanguage.KMETA, this), FakeFeatures(KarpfenLanguage.KMETA, this)),
            )
        }
    }

    @Test
    fun `the default services work without a project analysis`() {
        val services = defaultLanguageServices(MutableStateFlow(ProjectAnalysis.EMPTY))
        val text = "STATEMACHINE ATTACHED TO \"R\" { STATES { STATE \"a\" { } } TRANSITIONS { TRANSITION \"a\" -> \"a\" { } } }"
        val loose = ExampleProject.document(Path.of("/elsewhere/m.kstates"), text)

        assertEquals(listOf("a"), services.complete(loose, loose.text.text.lastIndexOf("\"a\"") + 1).map { it.label })
        assertIs<List<Location>>(services.definition(loose, loose.text.text.lastIndexOf("\"a\"") + 1)).single()
        assertTrue(services.highlight(loose).isNotEmpty())
    }
}
