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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.ExampleOrigin
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.TextFileStore
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.core.workspace.fixtures.openDocument
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.events.DomainOrigin
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers preparing a deployment from a workspace. Every synthesized model but the ring prepares without errors, and
 * the ring is blocked by its reference loop. The event catalog lists exactly the plan's domains for every synthesized
 * model, for the single robot and for the runtime's four examples. Preparing saves open documents first, so what is
 * uploaded, checked and seeded is the edited text. An edited descriptor's configuration is deployed. An analysis that
 * still holds the descriptor's earlier text blocks the deployment. A document that cannot be saved blocks the
 * deployment as well.
 */
class RuntimeSessionPreparationTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `every synthesized model but the ring prepares without errors, and the ring is blocked by its reference loop`() = runTest {
        val sessions = sessions(this)
        val workspace = TestWorkspaces(this).open(root, languageFeatures = TestWorkspaces.ALL_FEATURES)
        val descriptors = SynthesizedModel.entries.associateWith { SynthesizedModels.copyTo(root.resolve(it.folder), it, DescriptorTiming.NONE) }

        val preparations = descriptors.mapValues { (_, descriptor) -> sessions.prepare(workspace, descriptor) }

        (SynthesizedModel.entries - SynthesizedModel.RING).forEach { model ->
            val prepared = assertIs<Preparation.Ready>(preparations.getValue(model), "$model").prepared
            assertTrue(prepared.plan.findings.none { it.severity == Severity.ERROR }, "$model: ${prepared.plan.findings}")
            assertEquals(prepared.plan.attachments.map { it.attachedTo }, prepared.layouts.map { it.attachedTo }, "$model")
            assertEquals(prepared.plan.modelObjectIds, prepared.seed.objects.keys.filter { it.isNotEmpty() }.toSet(), "$model")
        }
        val ring = assertIs<Preparation.Blocked>(preparations.getValue(SynthesizedModel.RING)).findings
        assertEquals(listOf(DeployProblemCodes.REFERENCE_LOOP), ring.filter { it.severity == Severity.ERROR }.map { it.code })
    }

    @Test
    fun `the event catalog lists exactly the plan's domains for every model and example`() = runTest {
        SynthesizedModel.entries.filter { it != SynthesizedModel.RING }.forEach { SynthesizedModels.copyTo(root.resolve(it.folder), it, DescriptorTiming.NONE) }
        DescriptorExamples.copyExplicitContext(root.resolve("robot"))
        describeRuntimeExamples(this, root.resolve("examples"))
        val workspace = TestWorkspaces(this).open(root, languageFeatures = TestWorkspaces.ALL_FEATURES)
        val descriptors = Files.walk(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".kenv.json") }.sorted().toList() }

        val prepared = descriptors.map { descriptor -> assertIs<Preparation.Ready>(sessions(this).prepare(workspace, descriptor), "$descriptor").prepared }

        assertEquals(SynthesizedModel.entries.size - 1 + 1 + RUNTIME_EXAMPLES.size, prepared.size)
        prepared.forEach { deployment ->
            assertEquals(deployment.plan.domains, deployment.catalog.domains.map { it.name }, deployment.plan.name)
        }
        val lamp = prepared.single { it.plan.name == "lamp" }.catalog
        assertEquals(listOf("service" to DomainOrigin.DECLARED, "switch" to DomainOrigin.DECLARED), lamp.domains.map { it.name to it.origin })
        val metronome = prepared.single { it.plan.name == "metronome" }.catalog
        assertEquals(listOf("beat" to DomainOrigin.DECLARED, "tick" to DomainOrigin.USED_ONLY), metronome.domains.map { it.name to it.origin })
    }

    @Test
    fun `preparing saves the open documents first, so the edited text is uploaded, checked and seeded`() = runTest {
        val descriptor = SynthesizedModels.copyTo(root.resolve("lamp"), SynthesizedModel.LAMP, DescriptorTiming.NONE)
        val workspace = TestWorkspaces(this).open(root)
        val model = root.resolve("lamp/lamp.kmodel")
        val document = workspace.openDocument(model)
        val text = document.session.text
        val offset = text.snapshot.value.text.indexOf("\"off\"")
        text.replaceAll(text.snapshot.value.text.replaceRange(offset, offset + 5, "\"dark\""), EditOrigin.Typing)

        val prepared = assertIs<Preparation.Ready>(sessions(this).prepare(workspace, descriptor)).prepared

        assertTrue("\"dark\"" in Files.readString(model), "the edit was saved")
        assertTrue("\"dark\"" in prepared.plan.model.snapshot.text, "the saved text is uploaded")
        assertEquals("dark", prepared.seed.objectOf("lamp").text("mode"), "the seed is the saved text")
        assertEquals(false, document.isDirty.value)
    }

    @Test
    fun `an edited descriptor is saved and deployed with the configuration of its saved text`() = runTest {
        val descriptor = SynthesizedModels.copyTo(root.resolve("lamp"), SynthesizedModel.LAMP, DescriptorTiming(tickDelayMs = 50))
        val workspace = TestWorkspaces(this).open(root)
        val document = workspace.openDocument(descriptor)
        editTickDelay(document, from = 50, to = 777)

        val prepared = assertIs<Preparation.Ready>(sessions(this).prepare(workspace, descriptor)).prepared

        assertTrue("\"tickDelayMs\": 777" in Files.readString(descriptor), "the edit was saved")
        assertEquals(777, prepared.plan.configuration.tickDelayMs)
    }

    @Test
    fun `a descriptor whose analysis still holds its earlier text blocks the deployment instead of deploying its old configuration`() = runTest {
        val descriptor = SynthesizedModels.copyTo(root.resolve("lamp"), SynthesizedModel.LAMP, DescriptorTiming(tickDelayMs = 50))
        val workspace = TestWorkspaces(this).open(root)
        val document = workspace.openDocument(descriptor)
        val earlier = workspace.awaitAnalysis()
        editTickDelay(document, from = 50, to = 777)
        val lagging = object : Workspace by workspace {
            override suspend fun awaitAnalysis(): ProjectAnalysis = earlier
        }

        val findings = assertIs<Preparation.Blocked>(sessions(this).prepare(lagging, descriptor)).findings

        assertTrue("\"tickDelayMs\": 777" in Files.readString(descriptor), "the edit was saved")
        assertEquals(false, document.isDirty.value)
        val unsaved = findings.single { it.severity == Severity.ERROR }
        assertEquals(DeployProblemCodes.UNSAVED_FILE, unsaved.code)
        assertEquals(descriptor.toAbsolutePath().normalize(), unsaved.location?.file?.toAbsolutePath()?.normalize())
    }

    @Test
    fun `a document that cannot be saved blocks the deployment`() = runTest {
        val descriptor = SynthesizedModels.copyTo(root.resolve("lamp"), SynthesizedModel.LAMP, DescriptorTiming.NONE)
        val workspaces = TestWorkspaces(this)
        val failingStore = object : TextFileStore by TextFileStore() {
            override fun write(path: Path, content: String, withBom: Boolean) = throw IOException("the disk is full")
        }
        val workspace = Workspace(root, backgroundScope, workspaces.options(), defaultLanguageRegistry(), fileStore = failingStore, fileWatcher = workspaces.watcher)
        val document = workspace.openDocument(root.resolve("lamp/lamp.kmodel"))
        document.session.text.replaceAll(document.session.text.snapshot.value.text + "\n", EditOrigin.Typing)

        val blocked = assertIs<Preparation.Blocked>(sessions(this).prepare(workspace, descriptor)).findings

        val problem = blocked.single()
        assertEquals(DeployProblemCodes.SAVE_FAILED to Severity.ERROR, problem.code to problem.severity)
        assertEquals(root.resolve("lamp/lamp.kmodel").toAbsolutePath().normalize(), problem.location?.file?.toAbsolutePath()?.normalize())
        assertTrue("the disk is full" in problem.message, problem.message)
    }

    /** Replaces the tick delay [from] by [to] in the text of the open descriptor [document], as typing does. */
    private fun editTickDelay(document: OpenDocument, from: Long, to: Long) {
        val text = document.session.text
        text.replaceAll(text.snapshot.value.text.replace("\"tickDelayMs\": $from", "\"tickDelayMs\": $to"), EditOrigin.Typing)
    }

    /** The sessions of a test on its virtual time. */
    private fun sessions(scope: TestScope): RuntimeSessions =
        RuntimeSessions(RuntimeSessionOptions(dispatcher = TestWorkspaces(scope).dispatcher, timeSource = scope.testScheduler.timeSource, random = Random(1)))

    /**
     * Copies the runtime's examples below [target] and writes a descriptor into each of the four example folders. Each
     * descriptor names its files and attaches each machine to the best candidate object, as the template of a new
     * descriptor does.
     */
    private suspend fun describeRuntimeExamples(scope: TestScope, target: Path) {
        Examples.copyTo(target)
        val analysis = TestWorkspaces(scope).open(target, languageFeatures = TestWorkspaces.ALL_FEATURES).awaitAnalysis()
        RUNTIME_EXAMPLES.forEach { name ->
            val context = analysis.contexts.single {
                it.context.directory.fileName.toString() == name &&
                    it.context.directory.parent.fileName.toString() == ExampleOrigin.KARPFEN_RUNTIME.directoryName
            }
            Files.writeString(context.context.directory.resolve("$name.kenv.json"), descriptorOf(analysis, context))
        }
    }

    /** The descriptor text of the implicit [context] of [analysis]. */
    private fun descriptorOf(analysis: ProjectAnalysis, context: ContextAnalysis): String {
        val modeling = context.context
        val model = modeling.models.single()
        val symbols = ContextSymbols(analysis)
        val machines = modeling.stateMachines.joinToString(",\n") { binding ->
            val key = symbols.attachmentCandidates(model, binding.file).first { it.suitsMachine }.modelObject.key
            "    { \"file\": \"${binding.file.fileName}\", \"attachedTo\": \"$key\" }"
        }
        val events = modeling.events?.let { "  \"events\": \"${it.fileName}\",\n" }.orEmpty()
        return "{\n  \"metamodel\": \"${modeling.metamodel?.fileName}\",\n$events  \"model\": \"${model.fileName}\",\n" +
            "  \"stateMachines\": [\n$machines\n  ]\n}\n"
    }

    private companion object {
        /** The directories of the runtime's four examples. */
        val RUNTIME_EXAMPLES = listOf("single_robot_example", "delivery_drone_example", "autonomous_car_example", "drone_swarm_example")
    }
}
