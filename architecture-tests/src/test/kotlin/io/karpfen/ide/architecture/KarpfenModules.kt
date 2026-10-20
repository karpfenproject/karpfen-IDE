package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/** The karpfen IDE modules as seen by the architecture tests. */
internal object KarpfenModules {

    /**
     * Module directory (relative to the repository root) -> package prefix of all of its code.
     * Must list every module included in settings.gradle.kts.
     */
    val packagePrefixes: Map<String, String> = linkedMapOf(
        "core/core-text" to "io.karpfen.ide.core.text",
        "core/core-document" to "io.karpfen.ide.core.document",
        "core/core-workspace" to "io.karpfen.ide.core.workspace",
        "core/core-lang" to "io.karpfen.ide.core.lang",
        "runtime/runtime-protocol" to "io.karpfen.ide.runtime.protocol",
        "runtime/runtime-session" to "io.karpfen.ide.runtime.session",
        "runtime/runtime-host" to "io.karpfen.ide.runtime.host",
        "notation/notation-diagram" to "io.karpfen.ide.notation.diagram",
        "notation/notation-sync" to "io.karpfen.ide.notation.sync",
        "bridges/bridge-api" to "io.karpfen.ide.bridges.api",
        "bridges/bridge-mapping" to "io.karpfen.ide.bridges.mapping",
        "bridges/bridge-mqtt" to "io.karpfen.ide.bridges.mqtt",
        "bridges/bridge-ros" to "io.karpfen.ide.bridges.ros",
        "bridges/bridge-runner" to "io.karpfen.ide.bridges.runner",
        "ui/ui-foundation" to "io.karpfen.ide.ui.foundation",
        "ui/ui-editor" to "io.karpfen.ide.ui.editor",
        "ui/ui-diagram" to "io.karpfen.ide.ui.diagram",
        "ui/ui-workbench" to "io.karpfen.ide.ui.workbench",
        "app/ide" to "io.karpfen.ide.app",
        "tools/cli" to "io.karpfen.ide.tools.cli",
        "architecture-tests" to "io.karpfen.ide.architecture",
    )

    /** Directories whose Kotlin files are build tooling, not product code. */
    private val nonProductModules = setOf("build-logic")

    /**
     * Every Kotlin source file (`src/<sourceSet>/...kt`) in the repository except build tooling.
     * Konsist already skips hidden directories and build output; build scripts (`*.gradle.kts`) and
     * build-logic/ are filtered out here.
     */
    val sourceFiles: List<KoFileDeclaration> by lazy {
        Konsist.scopeFromProject().files.filter { file ->
            file.path.endsWith(".kt") &&
                "/src/" in file.projectPath.replace('\\', '/') &&
                file.moduleName.replace('\\', '/') !in nonProductModules
        }
    }

    /** Kotlin files of the karpfen modules listed in [packagePrefixes] (main and test source sets). */
    val moduleFiles: List<KoFileDeclaration> by lazy {
        sourceFiles.filter { it.moduleDir in packagePrefixes }
    }

    /** Production code of the karpfen modules. */
    val mainFiles: List<KoFileDeclaration> by lazy {
        moduleFiles.filter { it.sourceSetName == "main" }
    }

    /** Files whose package is [packagePrefix] or a sub-package of it. */
    fun filesInPackage(packagePrefix: String): List<KoFileDeclaration> =
        moduleFiles.filter { it.packageName.isWithin(packagePrefix) }
}

/** Module directory relative to the repository root, with '/' separators (e.g. `core/core-text`). */
internal val KoFileDeclaration.moduleDir: String
    get() = moduleName.replace('\\', '/')

/** The declared package, or "" for the default package. */
internal val KoFileDeclaration.packageName: String
    get() = packagee?.name ?: ""

/** True if this package or import name equals [prefix] or lies below it (`a.b` is within `a`, `ab` is not). */
internal fun String.isWithin(prefix: String): Boolean = this == prefix || startsWith("$prefix.")

/** True if this name lies within any of [prefixes]. */
internal fun String.isWithinAny(prefixes: Collection<String>): Boolean = prefixes.any { isWithin(it) }
