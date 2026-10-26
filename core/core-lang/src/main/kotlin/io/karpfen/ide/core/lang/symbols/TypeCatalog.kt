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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest

/**
 * The shared mechanic of the types of a modeling context, one collaborator for all languages instead of a copy per
 * language: the types visible from a document with their members and declaration [Location]s, as IDE-owned values, so
 * the dsl-tools metamodel (`io.karpfen.dsl.meta`) stays behind this interface. Used by completion, navigation and hover
 * of all three languages and by the `ModelPathResolver`.
 *
 * Implementations are thread-safe and convert the dsl-tools metamodels of a context into [TypeInfo]s once per analysis
 * (memoized by `ContextAnalysis` identity, which changes with every analysis generation, keeping only the latest), so a
 * hover, a completion or a path segment never walks the mutable dsl-tools objects again: only immutable values cross
 * threads. The document's own declarations come from its parse and are not memoized.
 */
public interface TypeCatalog {

    /**
     * The types visible from [request]'s document, in this order (a name appears once, the first wins): the types
     * declared in the document itself when it is a `.kmeta` file (from its parse, so they are known before the
     * analysis reads it), then the context's domain metamodel, then its event types. Never throws.
     *
     * Event types ([TypeOrigin.EVENTS]) are the TYPE declarations of the context's events file itself (its
     * `AnalyzedFile.parse` syntax index), with members from the events metamodel that dsl-tools read
     * (`ContextAnalysis.events`) — never all of `events.types`, which dsl-tools fills with the domain base types too.
     * So a provider listing event names or event types offers no domain type.
     */
    public fun types(request: FeatureRequest): List<TypeInfo>

    /** The visible type named [name], or null. */
    public fun type(request: FeatureRequest, name: String): TypeInfo? = types(request).firstOrNull { it.name == name }

    /**
     * The types of [request]'s modeling context alone — its domain types, then its event types, a name once — without
     * the requesting document's own declarations; what the analyzed files of the context see (e.g. when resolving the
     * model paths of another `.kstates` file). The default filters [types]; implementations that shadow context types
     * by document types return the shadowed ones too. Never throws.
     */
    public fun contextTypes(request: FeatureRequest): List<TypeInfo> = types(request).filter { it.origin != TypeOrigin.DOCUMENT }
}

/** The default [TypeCatalog] over the project analysis and the document's parse. */
public fun TypeCatalog(): TypeCatalog = ContextTypeCatalog()

/** Where a [TypeInfo] comes from. */
public enum class TypeOrigin {
    /** Declared in the requesting `.kmeta` document itself. */
    DOCUMENT,

    /** The context's domain metamodel. */
    DOMAIN,

    /** Declared in the context's event metamodel file (`EVENTS.kmeta`) itself; not the domain types it reuses. */
    EVENTS,
}

/**
 * A type and its members.
 *
 * @property description the KMeta description string, or null.
 * @property declaration the TYPE declaration's name range, or null if unknown.
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
     * Hover text, the type with its description and members: `type <name>`, the description on the next line if
     * there is one, then a blank line and one [MemberInfo.signature] per line if there are members.
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
 * A member of a type: `prop`, `has` or `knows`.
 *
 * @property typeName the element type (`number`, `Vector`, `Obstacle` for `list("Obstacle")`).
 * @property isList true for `list(…)`.
 * @property declaration the member declaration's name range, or null if unknown.
 */
public data class MemberInfo(
    public val name: String,
    public val kind: MemberKind,
    public val typeName: String,
    public val isList: Boolean,
    public val declaration: Location?,
) {
    /** True if [typeName] is a primitive (`number`, `string`, `boolean`, [KarpfenSyntax.PRIMITIVE_TYPES]). */
    public val isPrimitive: Boolean get() = typeName in KarpfenSyntax.PRIMITIVE_TYPES

    /** The hover signature: `prop x: number`, `has position: Vector`, `knows obstacles: list(Obstacle)`. */
    public val signature: String
        get() = "${kind.keyword} $name: ${if (isList) "list($typeName)" else typeName}"
}

/** Kind of a [MemberInfo]; [keyword] is how KMeta declares it. */
public enum class MemberKind(public val keyword: String) {
    /** `prop(…)`. */
    PROPERTY("prop"),

    /** `has(…)` (containment). */
    HAS("has"),

    /** `knows(…)` (reference). */
    KNOWS("knows"),
    ;

    public companion object {
        /** The kind declared with [keyword] (`prop`, `has`, `knows`), or null for any other word. */
        public fun ofKeyword(keyword: String): MemberKind? = entries.firstOrNull { it.keyword == keyword }
    }
}

/**
 * [TypeCatalog] reading the document's own TYPE declarations (a `.kmeta` document, from its parse outcomes by the
 * staleness rule, see [DocumentDeclarations]) and the context's types, converted once per `ContextAnalysis` by
 * [ContextTypesMemo] (see [ContextTypes] for the conversion, its completion by the syntax and its syntax fallback).
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
