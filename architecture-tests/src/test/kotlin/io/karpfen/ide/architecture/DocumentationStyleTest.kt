package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the documentation style of the project in every Kotlin source file and Gradle script ([DocumentedFiles]).
 *
 * Documentation lives in KDoc on declarations. A line comment or a plain block comment is not allowed anywhere, not
 * even as a trailing comment or a commented-out statement: code that needs an explanation gets a well-named function
 * or value with KDoc, or the explanation goes into the KDoc of the enclosing declaration. [KotlinComments] finds the
 * comments, so a URL or a comment delimiter inside a string, a character literal, a backtick name or KDoc is no
 * violation.
 *
 * Documentation is written for a human reader and names what it refers to in words instead of by an id that has to be
 * looked up in another document. [ReferenceIds] lists the ids that are rejected; they are searched in the text of every
 * comment.
 */
class DocumentationStyleTest {

    /** The files under guard, read once for all tests of this class. */
    private val files: List<RepositoryFile> by lazy { DocumentedFiles.all }

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
        assertEquals(
            emptyList(),
            paths.filter { path -> path.split('/').any { it == "build" || it.startsWith(".") } },
            "build output or hidden files are guarded",
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
    fun `documentation names what it refers to instead of using reference ids`() {
        val violations = files.flatMap { file ->
            KotlinComments.scan(file.text).flatMap { comment ->
                ReferenceIds.findIn(comment.text).map { id -> "${file.relativePath}:${comment.lineAt(id.offset)}  ${id.text}" }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Documentation must not refer to documents, sections or decisions by id. Name the thing in words, for " +
                "example \"the parse budget of the phase-1 specification\":\n" + violations.joinToString("\n"),
        )
    }
}
