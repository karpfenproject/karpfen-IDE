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
package io.karpfen.ide.runtime.protocol.source

/**
 * A fact of the client's view of the runtime, tied to the runtime's source. The [snippet] must occur verbatim in the
 * runtime file [file]. When the runtime changes the fact, the snippet no longer occurs, and the anchor names the file
 * and the snippet that went missing.
 *
 * @property file the runtime file, relative to the checkout's root.
 * @property snippet the exact text, as written in the runtime's Kotlin source.
 */
public data class SourceAnchor(public val file: String, public val snippet: String) {

    /** True if [snippet] occurs in [file] of [checkout]. */
    public fun holdsIn(checkout: RuntimeSourceCheckout): Boolean = snippet in checkout.text(file)

    override fun toString(): String = "$file: $snippet"
}

/**
 * The route declarations of a runtime file such as `HTTPRoutes.kt`. These are the calls `get("/…")`, `post("/…")` and
 * `put("/…")` in the order they are written. Each comes with its block, which is the text from the declaration up to
 * the next declaration or the end of the file. A route reads its query parameters inside its block, so the order of the
 * reads there is the order in which the runtime checks them.
 *
 * @property routes the declarations in source order.
 */
public class RouteDeclarations private constructor(public val routes: List<RouteDeclaration>) {

    public companion object {
        /** The pattern of a route declaration, which captures the verb and the path. */
        public val DECLARATION: Regex = Regex("""\b(get|post|put)\("(/[^"]+)"\)""")

        /** The pattern of a query parameter read, such as `call.request.queryParameters["<name>"]`. */
        public val PARAMETER_READ: Regex = Regex("""call\.request\.queryParameters\["([^"]+)"]""")

        /** The route declarations of the Kotlin source [text]. */
        public fun of(text: String): RouteDeclarations {
            val matches = DECLARATION.findAll(text).toList()
            return RouteDeclarations(
                matches.mapIndexed { index, match ->
                    val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
                    RouteDeclaration(match.groupValues[1].uppercase(), match.groupValues[2], text.substring(match.range.first, end))
                },
            )
        }
    }
}

/**
 * One route declaration of the runtime.
 *
 * @property verb the HTTP method in capitals, such as `POST`.
 * @property path the path, such as `/runEnvironment`.
 * @property block the declaration and its text up to the next declaration.
 */
public data class RouteDeclaration(public val verb: String, public val path: String, public val block: String) {
    /** The names of the query parameters the block reads, in the order it reads them. */
    public val parameterReads: List<String>
        get() = RouteDeclarations.PARAMETER_READ.findAll(block).map { it.groupValues[1] }.toList()
}
