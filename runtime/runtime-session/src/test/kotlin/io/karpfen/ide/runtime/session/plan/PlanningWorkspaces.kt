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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlinx.coroutines.test.TestScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Workspaces for planning tests. Synthesized models are copied into a folder of a temporary root, edited as the tests
 * need before opening, then opened on the virtual time of the test and analyzed.
 *
 * @property scope the test's scope, whose virtual time the workspaces run on.
 * @property root the absolute, normalized root of the workspaces.
 */
internal class PlanningWorkspaces(private val scope: TestScope, val root: Path) {
    /** Copies [model] into the folder named like it, with [timing] in its descriptor, and returns the descriptor. */
    fun copy(model: SynthesizedModel, timing: DescriptorTiming = DescriptorTiming.NONE): Path =
        SynthesizedModels.copyTo(root.resolve(model.folder), model, timing)

    /** Opens the workspace of [root] with [features] switched on. */
    fun open(features: Set<KstatesFeature> = PLAIN_FEATURES): Workspace = TestWorkspaces(scope).open(root, languageFeatures = features)

    /** Opens the workspace of [root] with [features] switched on and returns its first complete analysis. */
    suspend fun analyze(features: Set<KstatesFeature> = PLAIN_FEATURES): ProjectAnalysis = open(features).awaitAnalysis()

    /**
     * Plans [descriptor] from a fresh analysis of [root] with [features] switched on, with [unsaved] as the unsaved
     * paths.
     */
    suspend fun plan(descriptor: Path, unsaved: Set<Path> = emptySet(), features: Set<KstatesFeature> = PLAIN_FEATURES): PlanResult =
        DeploymentPlanner().plan(analyze(features), descriptor, unsaved)
}

/**
 * The language features that switch on no runtime feature: macros, and splits and joins. The workspaces of the
 * planning tests have them switched on unless a test says otherwise.
 */
internal val PLAIN_FEATURES: Set<KstatesFeature> = setOf(KstatesFeature.MACROS, KstatesFeature.PARALLEL)

/** Replaces [old] by [new] in the file, which must contain it. */
internal fun Path.replaceText(old: String, new: String) {
    val text = Files.readString(this)
    if (old !in text) fail("$this does not contain '$old'")
    Files.writeString(this, text.replace(old, new))
}

/** The plan of a ready result. A blocked result fails the test with its findings. */
internal fun PlanResult.ready(): DeploymentPlan =
    when (this) {
        is PlanResult.Ready -> plan
        is PlanResult.Blocked -> fail("expected a plan, but the deployment is blocked: $findings")
    }

/** The findings of a blocked result. A ready result fails the test. */
internal fun PlanResult.blocked(): List<DeployProblem> = assertIs<PlanResult.Blocked>(this, "expected a blocked deployment").findings

/** The code and severity of each problem. */
internal fun List<DeployProblem>.codes(): List<Pair<String, Severity>> = map { it.code to it.severity }
