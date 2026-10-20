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
