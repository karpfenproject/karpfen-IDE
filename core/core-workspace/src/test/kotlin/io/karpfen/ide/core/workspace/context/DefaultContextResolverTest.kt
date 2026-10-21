package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules of [ContextResolver] (spec §4, decisions 13 and 29) on hand-built trees. */
class DefaultContextResolverTest {

    private val resolver = ContextResolver()
    private val robotFiles = arrayOf("ctx/EVENTS.kmeta", "ctx/cleaning_robot.kmeta", "ctx/cleaning_robot.kmodel", "ctx/cleaning_robot.kstates")

    private fun rangeIn(text: String, quoted: String): TextRange =
        TextRange.ofLength(text.indexOf("\"$quoted\"") + 1, quoted.length)

    @Test
    fun `a valid descriptor defines one explicit context with paths against its directory`() {
        val tree = treeOf(*robotFiles, "ctx/robot.kenv.json")
        val read = descriptors("ctx/robot.kenv.json" to DescriptorExamples.text(DescriptorExamples.VALID))

        val resolution = resolver.resolve(tree, read)

        assertEquals(
            listOf(
                ModelingContext(
                    directory = ws("ctx"),
                    descriptor = ws("ctx/robot.kenv.json"),
                    metamodel = ws("ctx/cleaning_robot.kmeta"),
                    events = ws("ctx/EVENTS.kmeta"),
                    models = listOf(ws("ctx/cleaning_robot.kmodel")),
                    stateMachines = listOf(StateMachineBinding(ws("ctx/cleaning_robot.kstates"), DescriptorExamples.ATTACHED_TO)),
                ),
            ),
            resolution.contexts,
        )
        assertEquals(emptyMap(), resolution.diagnostics)
        assertEquals(emptyList(), resolution.looseFiles)
        assertEquals("robot", resolution.contexts.single().name)
    }

    @Test
    fun `a missing referenced file is an error at the reference, unnamed files of the directory are loose`() {
        val text = DescriptorExamples.text(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
        val tree = treeOf(*robotFiles, "ctx/robot.kenv.json")

        val resolution = resolver.resolve(tree, descriptors("ctx/robot.kenv.json" to text))

        val findings = resolution.diagnostics.getValue(ws("ctx/robot.kenv.json"))
        assertEquals(
            listOf(
                Triple(WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE, Severity.ERROR, rangeIn(text, "missing.kmodel")),
                Triple(WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY, Severity.WARNING, rangeIn(text, "tickDelay")),
            ),
            findings.map { Triple(it.code, it.severity, it.range) },
        )
        assertEquals(listOf(ws("ctx/missing.kmodel")), resolution.contexts.single().models)
        assertEquals(listOf(ws("ctx/cleaning_robot.kmodel")), resolution.looseFiles)
    }

    @Test
    fun `an unreadable descriptor defines no context but still covers its directory`() {
        val tree = treeOf(*robotFiles, "ctx/robot.kenv.json")

        val resolution = resolver.resolve(tree, descriptors("ctx/robot.kenv.json" to DescriptorExamples.text(DescriptorExamples.INVALID_JSON)))

        assertEquals(emptyList(), resolution.contexts)
        assertEquals(listOf(WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID), resolution.diagnostics.getValue(ws("ctx/robot.kenv.json")).map { it.code })
        assertEquals(robotFiles.map(::ws).sorted(), resolution.looseFiles.sorted())
    }

    @Test
    fun `paths may lead into sub-directories and up with dot-dot`() {
        val text = descriptorText("../shared/robot.kmeta", "models/robot.kmodel", "./machines/../machines/robot.kstates")
        val tree = treeOf("env/robot.kenv.json", "env/models/robot.kmodel", "env/machines/robot.kstates", "shared/robot.kmeta")

        val resolution = resolver.resolve(tree, descriptors("env/robot.kenv.json" to text))

        val explicit = resolution.contexts.first()
        assertEquals(ws("shared/robot.kmeta"), explicit.metamodel)
        assertEquals(listOf(ws("env/models/robot.kmodel")), explicit.models)
        assertEquals(listOf(ws("env/machines/robot.kstates")), explicit.stateMachines.map { it.file })
        assertNull(resolution.diagnostics[ws("env/robot.kenv.json")], "every reference exists")
        // Every file of the sub-directories is in the descriptor's context, so none of them forms an implicit one.
        assertEquals(listOf(ws("env")), resolution.contexts.map { it.directory })
    }

    @Test
    fun `files named by a descriptor form no implicit context and get no findings of their own`() {
        val tree = treeOf("robot.kenv.json", "meta/robot.kmeta", "models/robot.kmodel", "machines/robot.kstates")
        val text = descriptorText("meta/robot.kmeta", "models/robot.kmodel", "machines/robot.kstates")

        val resolution = resolver.resolve(tree, descriptors("robot.kenv.json" to text))

        assertEquals(listOf(ws("")), resolution.contexts.map { it.directory })
        assertEquals(emptyMap(), resolution.diagnostics, "no context-no-metamodel INFO on files the descriptor converts")
        assertEquals(emptyList(), resolution.looseFiles)
    }

    @Test
    fun `the files of a directory no descriptor names form its implicit context`() {
        val tree = treeOf("robot.kenv.json", "shared/robot.kmeta", "shared/robot.kmodel", "shared/other.kmodel")
        val text = descriptorText("shared/robot.kmeta", "shared/robot.kmodel")

        val resolution = resolver.resolve(tree, descriptors("robot.kenv.json" to text))

        val implicit = resolution.contexts.single { it.descriptor == null }
        assertEquals(ws("shared"), implicit.directory)
        assertEquals(listOf(ws("shared/other.kmodel")), implicit.files)
        assertEquals(
            listOf(WorkspaceDiagnosticCodes.CONTEXT_NO_METAMODEL),
            resolution.diagnostics.getValue(ws("shared/other.kmodel")).map { it.code },
            "the directory's metamodel belongs to the descriptor's context",
        )
        assertEquals(setOf(ws("shared/other.kmodel")), resolution.diagnostics.keys)
    }

    @Test
    fun `two descriptors in one directory, a file named by both, the rest loose`() {
        val tree = treeOf("ctx/a.kmeta", "ctx/one.kmodel", "ctx/two.kmodel", "ctx/extra.kstates", "ctx/one.kenv.json", "ctx/two.kenv.json")
        val read = descriptors(
            "ctx/two.kenv.json" to descriptorText("a.kmeta", "two.kmodel"),
            "ctx/one.kenv.json" to descriptorText("a.kmeta", "one.kmodel"),
        )

        val resolution = resolver.resolve(tree, read)

        assertEquals(listOf(ws("ctx/one.kenv.json"), ws("ctx/two.kenv.json")), resolution.contexts.map { it.descriptor }, "by descriptor path")
        assertEquals(listOf("one", "two"), resolution.contextsOf(ws("ctx/a.kmeta")).map { it.name })
        assertEquals(listOf(ws("ctx/extra.kstates")), resolution.looseFiles)
        assertEquals(emptyMap(), resolution.diagnostics)
    }

    @Test
    fun `references to ignored, absent, directory and invalid paths are missing files`() {
        val text = descriptorText("build/a.kmeta", "models", "../../outside.kstates", "bad\\u0000name.kstates")
        val tree = treeOf("ctx/robot.kenv.json", "ctx/models/x.kmodel")

        val resolution = resolver.resolve(tree, descriptors("ctx/robot.kenv.json" to text))

        val missing = resolution.diagnostics.getValue(ws("ctx/robot.kenv.json"))
        assertEquals(4, missing.size)
        assertTrue(missing.all { it.code == WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE && it.severity == Severity.ERROR })
        assertEquals(listOf(WS_ROOT.resolve("../outside.kstates").normalize()), resolution.contexts.first().stateMachines.map { it.file }, "invalid paths are dropped")
    }

    @Test
    fun `a directory without descriptor forms an implicit context of its karpfen files`() {
        val tree = treeOf("robot/EVENTS.kmeta", "robot/robot.kmeta", "robot/b.kmodel", "robot/a.kmodel", "robot/robot.kstates", "robot/README.md", "docs/notes.txt")

        val resolution = resolver.resolve(tree, emptyMap())

        assertEquals(
            listOf(
                ModelingContext(
                    directory = ws("robot"),
                    descriptor = null,
                    metamodel = ws("robot/robot.kmeta"),
                    events = ws("robot/EVENTS.kmeta"),
                    models = listOf(ws("robot/a.kmodel"), ws("robot/b.kmodel")),
                    stateMachines = listOf(StateMachineBinding(ws("robot/robot.kstates"), attachedTo = null)),
                ),
            ),
            resolution.contexts,
        )
        assertTrue(resolution.contexts.single().isImplicit)
        assertEquals(emptyList(), resolution.looseFiles)
        assertEquals(emptyMap(), resolution.diagnostics)
    }

    @Test
    fun `several metamodels are an INFO on each, the alphabetically first is used and the others are loose`() {
        val tree = treeOf("ctx/b.kmeta", "ctx/A.kmeta", "ctx/c.kmeta", "ctx/m.kmodel")

        val resolution = resolver.resolve(tree, emptyMap())

        assertEquals(ws("ctx/A.kmeta"), resolution.contexts.single().metamodel)
        assertEquals(listOf(ws("ctx/b.kmeta"), ws("ctx/c.kmeta")), resolution.looseFiles)
        for (name in listOf("A.kmeta", "b.kmeta", "c.kmeta")) {
            val info = resolution.diagnostics.getValue(ws("ctx/$name")).single()
            assertEquals(WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS, info.code)
            assertEquals(Severity.INFO, info.severity)
            assertEquals(TextRange.EMPTY, info.range)
        }
    }

    @Test
    fun `models and state machines without metamodel get an INFO`() {
        val tree = treeOf("ctx/EVENTS.kmeta", "ctx/m.kmodel", "ctx/s.kstates", "events-only/EVENTS.kmeta")

        val resolution = resolver.resolve(tree, emptyMap())

        val context = resolution.contexts.first()
        assertNull(context.metamodel)
        assertEquals(setOf(ws("ctx/m.kmodel"), ws("ctx/s.kstates")), resolution.diagnostics.keys)
        assertTrue(resolution.diagnostics.values.flatten().all { it.code == WorkspaceDiagnosticCodes.CONTEXT_NO_METAMODEL && it.severity == Severity.INFO })
        assertEquals(listOf(ws("ctx"), ws("events-only")), resolution.contexts.map { it.directory })
    }

    @Test
    fun `explicit contexts come before implicit ones and loose files are exactly the karpfen files in no context`() {
        val tree = treeOf("a/x.kmeta", "z/robot.kenv.json", "z/robot.kmeta", "z/robot.kmodel", "z/unnamed.kstates", "z/notes.md")

        val resolution = resolver.resolve(tree, descriptors("z/robot.kenv.json" to descriptorText("robot.kmeta", "robot.kmodel")))

        assertEquals(listOf(ws("z/robot.kenv.json"), null), resolution.contexts.map { it.descriptor })
        val inContexts = resolution.contexts.flatMap { it.files }.toSet()
        val karpfen = tree.files().filter { it.kind.isKarpfenSource }.map { it.path }
        assertEquals(karpfen.filter { it !in inContexts }, resolution.looseFiles)
        assertEquals(listOf(ws("z/unnamed.kstates")), resolution.looseFiles)
    }
}
