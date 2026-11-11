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
package io.karpfen.ide.core.lang.fixtures

import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.services.ProviderFailure
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import java.util.Collections
import kotlin.test.assertEquals

/**
 * The default language services over an analysis, failing a test as soon as the facade reports a provider failure, so
 * the facade's catch-all never hides a provider exception.
 */
class ServicesProbe(analysis: ProjectAnalysis = ExampleProject.analysis) {

    private val failures: MutableList<ProviderFailure> = Collections.synchronizedList(ArrayList())

    /** The services under test. */
    val services: LanguageServices = ExampleProject.services(analysis, failures)

    /** The completion items at [offset]. It fails on a provider failure. */
    fun complete(document: LanguageDocument, offset: Int): List<CompletionItem> = checked { services.complete(document, offset) }

    /** The definitions at [offset]. It fails on a provider failure. */
    fun definition(document: LanguageDocument, offset: Int): List<Location> = checked { services.definition(document, offset) }

    /** The references at [offset]. It fails on a provider failure. */
    fun references(document: LanguageDocument, offset: Int): List<Location> = checked { services.references(document, offset) }

    /** The hover at [offset]. It fails on a provider failure. */
    fun hover(document: LanguageDocument, offset: Int): Hover? = checked { services.hover(document, offset) }

    /** The bracket match at [offset]. It fails on a provider failure. */
    fun matchingBracket(document: LanguageDocument, offset: Int): BracketMatch? = checked { services.matchingBracket(document, offset) }

    /** The labels of the completion items at [offset], sorted like the editor does for an empty filter. */
    fun labels(document: LanguageDocument, offset: Int): List<String> =
        complete(document, offset).sortedWith(compareBy({ it.sortKey }, { it.label })).map { it.label }

    /** Fails if a provider failure was reported. */
    fun assertNoFailures() = assertEquals(emptyList(), failures.toList().map { "${it.operation} ${it.language}: ${it.error}" })

    /** The result of [call], after asserting that no provider failed. */
    private fun <T> checked(call: () -> T): T = call().also { assertNoFailures() }
}

/** The text that the range of [location] covers in [text]. The range must belong to [text]. */
fun textAt(text: String, location: Location): String = text.substring(location.range.start, location.range.end)
