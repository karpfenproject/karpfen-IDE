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
package io.karpfen.ide.architecture

/**
 * The Apache 2.0 license header that every Kotlin source file and Gradle script starts with: the copyright of Karl
 * Kegel and the notice of the Apache License, Version 2.0, in KDoc delimiters, character for character as the sources
 * of karpfen-dsl-tools carry it.
 *
 * The header stands once in a file, as its first lines and followed by a line break, before the file annotations, the
 * package declaration or the first statement of a script. In that one place it is the only KDoc that documents no
 * declaration, which [KDocPlacement] allows. A copy anywhere else, or a changed header, is a comment like any other.
 * Windows line breaks count as line breaks, so a checkout that converts them still has its headers.
 */
internal object LicenseHeader {

    /** The lines of the header, without line breaks. */
    private val LINES = listOf(
        "/**",
        " * Copyright 2026 Karl Kegel",
        " *",
        " * Licensed under the Apache License, Version 2.0 (the \"License\");",
        " * you may not use this file except in compliance with the License.",
        " * You may obtain a copy of the License at",
        " *",
        " * http://www.apache.org/licenses/LICENSE-2.0",
        " *",
        " * Unless required by applicable law or agreed to in writing, software",
        " * distributed under the License is distributed on an \"AS IS\" BASIS,",
        " * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
        " * See the License for the specific language governing permissions and",
        " * limitations under the License.",
        " */",
    )

    /** The header from its opening to its closing delimiter, with '\n' line breaks. */
    val text: String = LINES.joinToString("\n")

    /** True if the comment [placed] in a file is the header at the top of that file. */
    fun isHeader(placed: PlacedComment): Boolean = placed.offset == 0 && withLineFeeds(placed.comment.text) == text

    /**
     * Why [source], the text of a Kotlin source file or Gradle script, breaks the header rule, or null if it starts
     * with the header and a line break and holds no second copy.
     */
    fun violationIn(source: String): String? {
        val normalized = withLineFeeds(source)
        return when {
            !normalized.startsWith(text + "\n") -> "does not start with the license header"
            normalized.indexOf(text, startIndex = text.length) >= 0 -> "repeats the license header"
            else -> null
        }
    }

    /** [source] with every Windows line break replaced by a line feed. */
    private fun withLineFeeds(source: String): String = source.replace("\r\n", "\n")
}
