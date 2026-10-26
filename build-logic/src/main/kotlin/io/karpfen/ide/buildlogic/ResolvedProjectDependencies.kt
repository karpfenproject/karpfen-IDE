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
package io.karpfen.ide.buildlogic

import io.karpfen.ide.buildlogic.ModuleDependencyRules.DeclaredDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/** Extracts the project dependencies that a resolved classpath really contains, for [VerifyModuleDependencies]. */
object ResolvedProjectDependencies {

    /**
     * The first-level project dependencies of the graph rooted at [root] (the module itself), encoded with
     * [DeclaredDependency.encode] under the name of the resolved [configuration].
     *
     * Only projects of the module's own build count: an included build that substitutes an external module
     * (karpfen-dsl-tools in composite mode) is not part of the module dependency matrix. Transitive project
     * dependencies are checked in the module that declares them.
     */
    fun firstLevel(configuration: String, root: ResolvedComponentResult): List<String> {
        val ownBuild = (root.id as? ProjectComponentIdentifier)?.build?.buildPath
        return root.dependencies.asSequence()
            .filterIsInstance<ResolvedDependencyResult>()
            .filterNot { it.isConstraint }
            .mapNotNull { it.selected.id as? ProjectComponentIdentifier }
            .filter { it.build.buildPath == ownBuild }
            .map { DeclaredDependency(configuration, it.projectPath).encode() }
            .distinct()
            .sorted()
            .toList()
    }
}
