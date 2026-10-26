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
 * Guards the documentation style of the project in every Kotlin source file and Gradle script ([DocumentedFiles.all])
 * and, for reference ids, in the other text documents ([DocumentedFiles.documents]).
 *
 * Documentation lives in KDoc on declarations. A line comment or a plain block comment is not allowed anywhere, not
 * even as a trailing comment or a commented-out statement: code that needs an explanation gets a well-named function
 * or value with KDoc, or the explanation goes into the KDoc of the enclosing declaration. [KotlinComments] finds the
 * comments, so a URL or a comment delimiter inside a string, a character literal, a backtick name or KDoc is no
 * violation. KDoc itself must stand where it documents a declaration ([KDocPlacement]), so it cannot stand in for a
 * trailing comment or a comment between statements.
 *
 * Every one of these files starts with the Apache 2.0 license header ([LicenseHeader]), exactly once and before
 * anything else. It is KDoc in form, and the one KDoc that documents no declaration.
 *
 * Documentation is written for a human reader and names what it refers to in words instead of by an id that has to be
 * looked up in another document. [ReferenceIds] lists the ids that are rejected; they are searched in the text of every
 * comment and in the whole text of the markdown documents, the CI workflows, the XML resources and the version
 * catalog.
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
    fun `the guarded documents are the maps, workflows, resources and catalog, not the archived records`() {
        val paths = documents.map { it.relativePath }.toSet()

        listOf(
            "README.md", "docs/codebase-guide.md", "docs/adr/README.md", "docs/plans/increments-after-phase-1.md",
            ".github/workflows/build.yml", "app/ide/src/main/resources/logback.xml", "gradle/libs.versions.toml",
        ).forEach { assertTrue(it in paths, "$it is missing from the guarded documents") }
        assertEquals(emptyList(), paths.filter { it.startsWith("docs/history/") }, "the archived records keep their ids")
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
            "Documentation must not refer to documents, sections or decisions by id. Name the thing in words, for " +
                "example \"the parse budget of the phase-1 specification\":\n" + violations.joinToString("\n"),
        )
    }

    /** The 1-based line of the character at [offset] of [text]. */
    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1
}
