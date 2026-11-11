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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.runtime.session.problem.DeployProblem
import java.nio.file.Path

/**
 * Turns the analysis of a saved workspace into a [DeploymentPlan] for one environment descriptor, or into the problems
 * that block the deployment.
 *
 * The plan uploads the analyzed snapshots of the context's files, and the problems gate reads the diagnostics of
 * exactly those snapshots. So what the gate checked is what runs. The gate refuses a context without a metamodel or a
 * model, a file with an error and a file that still differs from its saved state. The preflight checks then refuse
 * what the runtime would mishandle, and warn about what it would do unexpectedly.
 *
 * [plan] is pure. It reads the analysis, never touches the wire and never suspends. It reads the dsl-tools models of
 * the analysis, so it runs where the analysis may be read. The plan it returns holds no dsl-tools object and may cross
 * threads.
 *
 * [plan] never throws for a broken workspace. Every finding is a [DeployProblem]. A [PlanResult.Blocked] holds at
 * least one ERROR. A [PlanResult.Ready] plan holds only warnings and infos.
 */
public interface DeploymentPlanner {
    /**
     * Plans the deployment of the explicit context of [descriptor] from [analysis]. [unsaved] holds the paths whose
     * open document still differs from its saved state or from the snapshot the analysis read. If it holds a file of
     * the context or the descriptor, the deployment is blocked.
     */
    public fun plan(analysis: ProjectAnalysis, descriptor: Path, unsaved: Set<Path>): PlanResult
}

/** Creates the planner with every check of the problems gate and every preflight check. */
public fun DeploymentPlanner(): DeploymentPlanner = DefaultDeploymentPlanner(PlanChecks.gate(), PlanChecks.preflight())

/** What planning a deployment returns, which is a plan or the problems that block it. Immutable. */
public sealed interface PlanResult {
    /**
     * The deployment can run.
     *
     * @property plan what it uploads and sets, with the warnings and infos of the checks.
     */
    public data class Ready(public val plan: DeploymentPlan) : PlanResult

    /**
     * The deployment must not run.
     *
     * @property findings every finding of the checks that ran, at least one of them an ERROR.
     */
    public data class Blocked(public val findings: List<DeployProblem>) : PlanResult {
        init {
            require(findings.any { it.severity == Severity.ERROR }) {
                "a blocked plan holds at least one ERROR: $findings"
            }
        }
    }
}
