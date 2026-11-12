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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the documentation style of the project. The rules read every Kotlin source file and Gradle script
 * ([DocumentedFiles.all]) and, for the rules on the whole text, the other text documents ([DocumentedFiles.documents]).
 *
 * Documentation lives in KDoc on declarations. A line comment or a plain block comment is not allowed anywhere, not
 * even as a trailing comment or a commented-out statement. Code that needs an explanation gets a well-named function
 * or value with KDoc, or the explanation goes into the KDoc of the enclosing declaration. [KotlinComments] finds the
 * comments, so a URL or a comment delimiter inside a string, a character literal, a backtick name or KDoc is no
 * violation. KDoc itself must stand where it documents a declaration ([KDocPlacement]), so it cannot stand in for a
 * trailing comment or a comment between statements.
 *
 * Every one of these files starts with the Apache 2.0 license header ([LicenseHeader]), exactly once and before
 * anything else. The header is KDoc in form, and it is the one KDoc that documents no declaration.
 *
 * Markdown is kept minimal. The only markdown files are the README, the three guides in `docs` and the license notice
 * of the example files that the test fixtures copy from other repositories ([ALLOWED_MARKDOWN]). Everything else a
 * reader of the code needs is in KDoc.
 *
 * Documentation is written for a human reader in plain sentences. It names what it refers to in words instead of by
 * an id that has to be looked up in another document. [ReferenceIds] lists the ids that are rejected. It also does
 * without dashes, middle dots, bullets and arrows ([TypographicCharacters]). Both rules search the text of every
 * comment and the whole text of the markdown documents, the XML resources and the version catalog.
 */
class DocumentationStyleTest {

    /** The Kotlin sources and Gradle scripts under guard, read once for all tests of this class. */
    private val files: List<RepositoryFile> by lazy { DocumentedFiles.all }

    /** The other text documents under guard, read once for all tests of this class. */
    private val documents: List<RepositoryFile> by lazy { DocumentedFiles.documents }

    @Test
    fun `the guarded files are the Kotlin sources and Gradle scripts of every module and of build-logic`() {
        val paths = files.map { it.relativePath }.toSet()
        val modules = KarpfenModules.packagePrefixes.keys
        val requiredScripts = modules.map { "$it/build.gradle.kts" } +
            listOf("settings.gradle.kts", "build.gradle.kts", "build-logic/settings.gradle.kts", "build-logic/build.gradle.kts")

        assertEquals(emptyList(), requiredScripts.filterNot { it in paths }, "Gradle scripts missing from the guarded files")
        assertEquals(
            emptyList(),
            modules.filterNot { module -> paths.any { it.startsWith("$module/src/main/") } },
            "modules whose sources are missing from the guarded files",
        )
        assertTrue(
            paths.any { it.startsWith("build-logic/src/main/kotlin/") && it.endsWith(".gradle.kts") },
            "the precompiled script plugins of build-logic are missing from the guarded files",
        )
        assertTrue(paths.any { it.startsWith("build-logic/src/test/") }, "the tests of build-logic are missing from the guarded files")
        assertTrue(paths.any { "/src/testFixtures/" in it }, "the test fixtures are missing from the guarded files")
        val outsideSources = paths.filter { path ->
            BuildOutput.contains(path) || path.split('/').takeWhile { it != "src" }.any { it.startsWith(".") }
        }
        assertEquals(emptyList(), outsideSources, "build output or hidden files are guarded")
    }

    @Test
    fun `the guarded documents are the guides, resources and catalog`() {
        val paths = documents.map { it.relativePath }.toSet()

        assertEquals(emptyList(), REQUIRED_DOCUMENTS.filterNot { it in paths }, "documents missing from the guarded documents")
    }

    @Test
    fun `markdown exists only as the README, the guides and the license notice of the example files`() {
        val markdown = DocumentedFiles.markdownPaths
        assertTrue("README.md" in markdown, "the README is not among the markdown files; this check would be vacuous")

        val unexpected = markdown.filterNot { it in ALLOWED_MARKDOWN }
        assertTrue(
            unexpected.isEmpty(),
            "Markdown is kept minimal. The only markdown files are ${ALLOWED_MARKDOWN.joinToString()}. Put the content " +
                "of these files into the KDoc of the code it explains, or into one of the guides in docs, and delete " +
                "them:\n" + unexpected.joinToString("\n"),
        )
    }

    @Test
    fun `Kotlin sources and Gradle scripts have no line comments and no block comments other than KDoc`() {
        val violations = files.flatMap { file ->
            KotlinComments.scan(file.text)
                .filter { it.kind != CommentKind.KDOC }
                .map { comment -> "${file.relativePath}:${comment.line}  ${comment.excerpt()}" }
        }
        assertTrue(
            violations.isEmpty(),
            "Line comments and block comments other than KDoc are not allowed. Document the declaration in KDoc, or " +
                "extract a well-named function or value and document that:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `every Kotlin source file and Gradle script starts with the license header`() {
        val violations = files.mapNotNull { file -> LicenseHeader.violationIn(file.text)?.let { "${file.relativePath}  $it" } }
        assertTrue(
            violations.isEmpty(),
            "Every Kotlin source file and Gradle script starts with the Apache 2.0 license header, copied exactly from " +
                "another source file, before its file annotations, its package or its first statement:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `KDoc stands on its own line before the declaration it documents`() {
        val violations = files.flatMap { file ->
            KDocPlacement.check(file.text, isScript = file.relativePath.endsWith(".kts")).map { misplaced ->
                "${file.relativePath}:${misplaced.comment.line}  ${misplaced.reason}: ${misplaced.comment.excerpt()}"
            }
        }
        assertTrue(
            violations.isEmpty(),
            "KDoc documents a declaration (or, in a script, a top-level statement). Move the explanation into the KDoc " +
                "of the enclosing declaration, or extract a well-named function or value and document that:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `documentation names what it refers to instead of using reference ids`() {
        val inComments = files.flatMap { file ->
            KotlinComments.scan(file.text).flatMap { comment ->
                ReferenceIds.findIn(comment.text).map { id -> "${file.relativePath}:${comment.lineAt(id.offset)}  ${id.text}" }
            }
        }
        val inDocuments = documents.flatMap { file ->
            ReferenceIds.findIn(file.text).map { id -> "${file.relativePath}:${lineOf(file.text, id.offset)}  ${id.text}" }
        }
        val violations = inComments + inDocuments
        assertTrue(
            violations.isEmpty(),
            "Documentation must not refer to documents, sections, decisions, phases or increments by id or number. Name " +
                "the thing in words, for example \"the decision that text files are the source of truth\":\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `documentation uses plain punctuation`() {
        val inComments = files.flatMap { file ->
            KotlinComments.scan(file.text).flatMap { comment ->
                TypographicCharacters.findIn(comment.text).map { found ->
                    "${file.relativePath}:${comment.lineAt(found.offset)}  ${found.description}"
                }
            }
        }
        val inDocuments = documents.flatMap { file ->
            TypographicCharacters.findIn(file.text).map { found ->
                "${file.relativePath}:${lineOf(file.text, found.offset)}  ${found.description}"
            }
        }
        val violations = inComments + inDocuments
        assertTrue(
            violations.isEmpty(),
            "Documentation does without dashes, middle dots, bullets and arrows. Write plain sentences: split a long " +
                "sentence in two, use a comma or a full stop, and write a verb such as \"becomes\" or \"returns\" " +
                "instead of an arrow:\n" + violations.joinToString("\n"),
        )
    }

    /** The 1-based line of the character at [offset] of [text]. */
    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1

    private companion object {
        /**
         * The documents that the guarded documents must include. They are the README, the three guides, the logging
         * configuration of the application and the version catalog, one of each kind.
         */
        val REQUIRED_DOCUMENTS = listOf(
            "README.md", "docs/getting-started.md", "docs/archdoc.md", "docs/devdoc.md",
            "app/ide/src/main/resources/logback.xml", "gradle/libs.versions.toml",
        )

        /**
         * The only markdown files of the repository. They are the README, the getting started guide, the architecture
         * guide, the developer guide and the license notice of the example files that core-document's test fixtures
         * copy from the karpfen-runtime and karpfen-dsl-tools repositories.
         */
        val ALLOWED_MARKDOWN = setOf(
            "README.md", "docs/getting-started.md", "docs/archdoc.md", "docs/devdoc.md",
            "core/core-document/src/testFixtures/resources/examples/NOTICE.md",
        )
    }
}
