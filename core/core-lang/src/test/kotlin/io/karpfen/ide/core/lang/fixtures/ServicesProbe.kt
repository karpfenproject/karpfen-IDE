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
 * The default language services over an analysis, failing a test as soon as the facade reports a provider failure
 * (its catch-all must not hide exceptions, spec §11 item 3).
 */
class ServicesProbe(analysis: ProjectAnalysis = ExampleProject.analysis) {

    private val failures: MutableList<ProviderFailure> = Collections.synchronizedList(ArrayList())

    /** The services under test. */
    val services: LanguageServices = ExampleProject.services(analysis, failures)

    fun complete(document: LanguageDocument, offset: Int): List<CompletionItem> = checked { services.complete(document, offset) }

    fun definition(document: LanguageDocument, offset: Int): List<Location> = checked { services.definition(document, offset) }

    fun references(document: LanguageDocument, offset: Int): List<Location> = checked { services.references(document, offset) }

    fun hover(document: LanguageDocument, offset: Int): Hover? = checked { services.hover(document, offset) }

    fun matchingBracket(document: LanguageDocument, offset: Int): BracketMatch? = checked { services.matchingBracket(document, offset) }

    /** The labels of the completion items at [offset], sorted like the editor does for an empty filter. */
    fun labels(document: LanguageDocument, offset: Int): List<String> =
        complete(document, offset).sortedWith(compareBy({ it.sortKey }, { it.label })).map { it.label }

    /** Fails if a provider failure was reported. */
    fun assertNoFailures() = assertEquals(emptyList(), failures.toList().map { "${it.operation} ${it.language}: ${it.error}" })

    private fun <T> checked(call: () -> T): T = call().also { assertNoFailures() }
}

/** The text of [location] in [text] (its range, which must belong to [text]). */
fun textAt(text: String, location: Location): String = text.substring(location.range.start, location.range.end)
