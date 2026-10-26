package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The git submodules are recognized from `.gitmodules`, and their sources stay out of the Konsist scope and of the
 * files the documentation style rules read, while the repository's own sources stay in.
 */
class SubmodulesTest {

    @Test
    fun `path entries are read from a gitmodules text`() {
        val text = """
            [submodule "karpfen-dsl-tools"]
            	path = karpfen-dsl-tools
            	url = https://github.com/karpfenproject/karpfen-dsl-tools.git
            [submodule "nested"]
                path = external/nested/
        """.trimIndent()
        assertEquals(listOf("karpfen-dsl-tools", "external/nested"), Submodules.parse(text))
    }

    @Test
    fun `the dsl-tools submodule is recorded and its directory is recognized`() {
        assertTrue("karpfen-dsl-tools" in Submodules.paths, "expected the karpfen-dsl-tools submodule in .gitmodules")
        assertTrue(Submodules.contains("karpfen-dsl-tools/src/main/kotlin/A.kt"))
        assertTrue(Submodules.contains("/karpfen-dsl-tools/build.gradle"))
        assertFalse(Submodules.contains("karpfen-dsl-tools-notes/readme.md"))
        assertFalse(Submodules.contains("core/core-text/src/main/kotlin/A.kt"))
    }

    @Test
    fun `no submodule file is part of the scanned code base`() {
        val konsistPaths = KarpfenModules.sourceFiles.map { it.projectPath.replace('\\', '/') } +
            KarpfenModules.buildToolingTestFiles.map { it.projectPath.replace('\\', '/') }
        assertTrue(konsistPaths.isNotEmpty(), "the Konsist scope is empty; this check would be vacuous")
        assertEquals(emptyList(), konsistPaths.filter(Submodules::contains))
        val documentedPaths = (DocumentedFiles.all + DocumentedFiles.documents).map { it.relativePath }
        assertTrue(documentedPaths.isNotEmpty(), "no documented files; this check would be vacuous")
        assertEquals(emptyList(), documentedPaths.filter(Submodules::contains))
    }
}
