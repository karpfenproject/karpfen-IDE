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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.ReadResult
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The analyzer of a workspace remembers its reads. An unchanged file is read once, however often the workspace is
 * analyzed. So an unchanged file that dsl-tools fails on is not logged again on every analysis, while its findings are
 * still reported on every analysis. A changed file is read again, and so is every model of a changed metamodel. The
 * end-to-end tests of the application show that what dsl-tools logs during a read names the file.
 */
class RememberedReadsTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    /** The real readers of dsl-tools, recording the text of every read. */
    private class RecordingReader(private val delegate: DslReader = DslReader()) : DslReader {
        /** The text of each read, in the order of the reads. */
        val texts = mutableListOf<String>()

        override fun kmeta(source: TextSnapshot, baseTypes: List<ClassType>, features: Set<KstatesFeature>): ReadResult<Metamodel> =
            delegate.kmeta(source.also(::record), baseTypes, features)

        override fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model> =
            delegate.kmodel(source.also(::record), metamodel)

        override fun kstates(source: TextSnapshot, features: Set<KstatesFeature>): ReadResult<StateMachine> =
            delegate.kstates(source.also(::record), features)

        /** Records the text of [source]. */
        private fun record(source: TextSnapshot) {
            texts += source.text
        }

        /**
         * The names of the files in [directory] whose texts were read, in the order of the reads, from the read with
         * the index [from] on.
         */
        fun filesReadIn(directory: Path, from: Int = 0): List<String> {
            val names = Files.list(directory).use { files -> files.toList().associateBy({ Files.readString(it) }, { it.fileName.toString() }) }
            return texts.drop(from).map { names.getValue(it) }
        }
    }

    /** Writes the files of the single-robot example into the folder `robot` and returns the folder. */
    private fun robotContext(): Path {
        val context = Files.createDirectories(root.resolve("robot"))
        Examples.files().filter { it.directory == DescriptorExamples.CONTEXT_DIRECTORY }
            .forEach { Files.writeString(context.resolve(it.fileName), it.text) }
        return context
    }

    @Test
    fun `an unchanged context is read once however often it is analyzed`() = runTest(timeout = 60.seconds) {
        val context = robotContext()
        val reader = RecordingReader()
        val analyzer = ProjectAnalyzer(defaultLanguageRegistry(), reader)
        val resolution = resolve(root)

        val analyses = (1L..3L).map { analyzer.analyze(resolution, DiskSources(), generation = it) }

        assertEquals(ROBOT_FILES, reader.filesReadIn(context).sorted())
        assertEquals(List(3) { analyses.first().diagnostics }, analyses.map { it.diagnostics })
        assertEquals(List(3) { analyses.first().contexts.single().models }, analyses.map { it.contexts.single().models })
    }

    @Test
    fun `an unchanged file with findings is read once and reported on every analysis`() = runTest(timeout = 60.seconds) {
        val context = Files.createDirectories(root.resolve("robot"))
        Files.writeString(context.resolve("robot.kmeta"), "type \"Robot\" \"A robot\" {\n    prop(\"speed\", \"number\")\n}\n")
        val model = context.resolve("robot.kmodel")
        Files.writeString(model, "make object \"r\":\"Robot\" {\n    prop(\"velocity\") -> \"2\"\n}\n")
        val reader = RecordingReader()
        val analyzer = ProjectAnalyzer(defaultLanguageRegistry(), reader)
        val resolution = resolve(root)

        val analyses = (1L..3L).map { analyzer.analyze(resolution, DiskSources(), generation = it) }

        assertEquals(listOf("robot.kmeta", "robot.kmodel"), reader.filesReadIn(context).sorted())
        analyses.forEach { assertEquals(listOf("kmodel.unknown-property"), it.diagnosticsFor(model).map { finding -> finding.code }) }
    }

    @Test
    fun `a changed metamodel is read again with its models, an unchanged state machine is not`() = runTest(timeout = 60.seconds) {
        val context = robotContext()
        val reader = RecordingReader()
        val analyzer = ProjectAnalyzer(defaultLanguageRegistry(), reader)
        val resolution = resolve(root)
        analyzer.analyze(resolution, DiskSources(), generation = 1)
        val firstReads = reader.texts.size
        val metamodel = context.resolve("cleaning_robot.kmeta")
        Files.writeString(metamodel, Files.readString(metamodel) + "\n// changed\n")

        analyzer.analyze(resolution, DiskSources(), generation = 2)

        val reread = reader.filesReadIn(context, from = firstReads).sorted()
        assertEquals(listOf("EVENTS.kmeta", "cleaning_robot.kmeta", "cleaning_robot.kmodel"), reread)
    }

    @Test
    fun `a changed set of language features reads the state machines and the metamodels again with what depends on them`() =
        runTest(timeout = 60.seconds) {
            val context = robotContext()
            val reader = RecordingReader()
            val analyzer = ProjectAnalyzer(defaultLanguageRegistry(), reader)
            val resolution = resolve(root)
            val history = setOf(KstatesFeature.HISTORY)
            analyzer.analyze(resolution, DiskSources(), generation = 1)
            val firstReads = reader.texts.size

            val changed = analyzer.analyze(resolution, DiskSources(), generation = 2, languageFeatures = history)
            val secondReads = reader.texts.size
            analyzer.analyze(resolution, DiskSources(), generation = 3, languageFeatures = history)

            assertEquals(ROBOT_FILES.sorted(), reader.filesReadIn(context, from = firstReads).sorted(), "the model depends on the metamodel")
            assertEquals(secondReads, reader.texts.size, "the same set is not read again")
            assertEquals(history, changed.languageFeatures)
        }

    private companion object {
        /** The files of the single-robot example, each of which dsl-tools reads, in alphabetical order. */
        val ROBOT_FILES = listOf("EVENTS.kmeta", "cleaning_robot.kmeta", "cleaning_robot.kmodel", "cleaning_robot.kstates")
    }
}
