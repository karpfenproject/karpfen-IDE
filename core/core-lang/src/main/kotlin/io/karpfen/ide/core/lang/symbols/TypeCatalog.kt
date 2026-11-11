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
package io.karpfen.ide.core.lang.symbols

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest

/**
 * The shared mechanic of the types of a modeling context. It is one collaborator for all languages instead of a copy
 * for each language. It gives the types visible from a document with their members and declaration [Location]s. These
 * are values the IDE owns, so the dsl-tools metamodel in `io.karpfen.dsl.meta` stays behind this interface. Completion,
 * navigation and hover of all three languages use it, and so does the `ModelPathResolver`.
 *
 * Implementations are thread-safe. They convert the dsl-tools metamodels of a context into [TypeInfo]s once per
 * analysis. The result is memoized by the identity of the `ContextAnalysis`, which changes with every analysis
 * generation, and only the latest is kept. So a hover, a completion or a path segment never walks the mutable dsl-tools
 * objects again, and only immutable values cross threads. The document's own declarations come from its parse and
 * are not memoized.
 */
public interface TypeCatalog {

    /**
     * The types visible from the document of [request]. Each name appears once, and the first one wins. First come the
     * types declared in the document itself when it is a `.kmeta` file. They come from its parse, so they are known
     * before the analysis reads the file. Then come the types of the domain metamodel of the context, and then its
     * event types. It never throws.
     *
     * The event types of origin [TypeOrigin.EVENTS] are the TYPE declarations of the events file of the context
     * itself, taken from the syntax index of its `AnalyzedFile.parse`. Their members come from the events metamodel
     * that dsl-tools read, which is `ContextAnalysis.events`. They are never all of `events.types`, because dsl-tools
     * fills that with the domain base types too. So a provider that lists event names or event types offers no domain
     * type.
     */
    public fun types(request: FeatureRequest): List<TypeInfo>

    /** The visible type named [name], or null. */
    public fun type(request: FeatureRequest, name: String): TypeInfo? = types(request).firstOrNull { it.name == name }

    /**
     * The types of the modeling context of [request] alone, without the requesting document's own declarations.
     * The domain types come first, then the event types, and each name appears once. These are the types the analyzed
     * files of the context see, for example when the model paths of another `.kstates` file are resolved. The default
     * filters [types]. Implementations that shadow context types by document types return the shadowed ones too. It
     * never throws.
     */
    public fun contextTypes(request: FeatureRequest): List<TypeInfo> = types(request).filter { it.origin != TypeOrigin.DOCUMENT }
}

/** The default [TypeCatalog] over the project analysis and the parse of the document. */
public fun TypeCatalog(): TypeCatalog = ContextTypeCatalog()

/** Where a [TypeInfo] comes from. */
public enum class TypeOrigin {
    /** Declared in the requesting `.kmeta` document itself. */
    DOCUMENT,

    /** The domain metamodel of the context. */
    DOMAIN,

    /**
     * Declared in the event metamodel file `EVENTS.kmeta` of the context itself. The domain types it reuses are not
     * included.
     */
    EVENTS,
}

/**
 * A type and its members.
 *
 * @property description the KMeta description string, or null.
 * @property declaration the name range of the TYPE declaration, or null if unknown.
 */
public data class TypeInfo(
    public val name: String,
    public val description: String?,
    public val members: List<MemberInfo>,
    public val origin: TypeOrigin,
    public val declaration: Location?,
) {
    /** The member named [name], or null. */
    public fun member(name: String): MemberInfo? = members.firstOrNull { it.name == name }

    /**
     * The hover text, which shows the type with its description and members. It starts with `type <name>`. The
     * description follows on the next line if there is one. If there are members, a blank line follows and then one
     * [MemberInfo.signature] per line.
     */
    public val hoverText: String
        get() = buildString {
            append("type ").append(name)
            description?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
            if (members.isNotEmpty()) {
                append('\n')
                members.forEach { append('\n').append(it.signature) }
            }
        }
}

/**
 * A `prop`, `has` or `knows` member of a type.
 *
 * @property typeName the element type, such as `number`, `Vector`, or `Obstacle` for `list("Obstacle")`.
 * @property isList true for `list(...)`.
 * @property declaration the name range of the member declaration, or null if unknown.
 */
public data class MemberInfo(
    public val name: String,
    public val kind: MemberKind,
    public val typeName: String,
    public val isList: Boolean,
    public val declaration: Location?,
) {
    /** True if [typeName] is one of the [KarpfenSyntax.PRIMITIVE_TYPES] `number`, `string` and `boolean`. */
    public val isPrimitive: Boolean get() = typeName in KarpfenSyntax.PRIMITIVE_TYPES

    /** The hover signature, such as `prop x: number`, `has position: Vector` or `knows obstacles: list(Obstacle)`. */
    public val signature: String
        get() = "${kind.keyword} $name: ${if (isList) "list($typeName)" else typeName}"
}

/**
 * The kind of a [MemberInfo]. [keyword] is how KMeta declares it. The keywords of the relations are the ones dsl-tools
 * publishes as [AssociationType.keyword], so the IDE names them exactly as dsl-tools reads them.
 */
public enum class MemberKind(public val keyword: String) {
    /** `prop(...)`. */
    PROPERTY("prop"),

    /** `has(...)`, a containment. */
    HAS(AssociationType.EMBEDDED.keyword),

    /** `knows(...)`, a reference. */
    KNOWS(AssociationType.LINK.keyword),
    ;

    public companion object {
        /** The kind declared with [keyword], which is `prop`, `has` or `knows`, or null for any other word. */
        public fun ofKeyword(keyword: String): MemberKind? = entries.firstOrNull { it.keyword == keyword }
    }
}

/**
 * The [TypeCatalog] that reads a `.kmeta` document's own TYPE declarations and the types of the context. The
 * declarations of the document come from its parse outcomes by the staleness rule, through [DocumentDeclarations].
 * The types of the context are converted once per `ContextAnalysis` by [ContextTypesMemo]. [ContextTypes] describes
 * the conversion, how the syntax completes it and how it falls back to the syntax.
 */
internal class ContextTypeCatalog(private val memo: ContextTypesMemo = ContextTypesMemo()) : TypeCatalog {

    override fun types(request: FeatureRequest): List<TypeInfo> {
        val document = request.document
        val own = if (document.language == KarpfenLanguage.KMETA) {
            SyntaxTypes.read(DocumentDeclarations(document).located(), TypeOrigin.DOCUMENT)
        } else {
            emptyList()
        }
        return (own + contextTypes(request)).distinctBy { it.name }
    }

    override fun contextTypes(request: FeatureRequest): List<TypeInfo> =
        request.contextAnalysis?.let { memo.typesOf(request.project, it).all }.orEmpty()
}
