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
package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/**
 * The modules of the karpfen IDE as the architecture tests see them, with the Kotlin files that Konsist reads from them
 * and the two adapter packages that confine dsl-tools and ANTLR.
 */
internal object KarpfenModules {

    /**
     * Maps each module directory, relative to the repository root, to the package prefix of all of its code. It must
     * list every module that settings.gradle.kts includes, because production code outside these modules fails
     * `PackageLayoutTest`.
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

    /**
     * The only package that may use ANTLR and the lexers and parsers that dsl-tools generates from its grammars
     * (`io.karpfen.dsl.grammar.kmeta`, `.kmodel` and `.kstates`). Its sub-packages count as part of it. The IDE takes
     * source positions from the parse trees. Every use stays in one internal package, so the source maps that
     * dsl-tools ships with its readers (`SourceMap` in `io.karpfen.dsl.source`) can replace it without touching the
     * rest of the IDE.
     */
    const val ANTLR_ADAPTER_PACKAGE: String = "io.karpfen.ide.core.document.antlr"

    /**
     * The only package that may use dsl-tools beyond its semantic model, because adapters isolate foreign APIs. Its
     * sub-packages count as part of it. This covers the readers (`io.karpfen.dsl.reader`), the diagnostics and source
     * positions they report (`io.karpfen.dsl.diagnostics`, `io.karpfen.dsl.source`), the printers
     * (`io.karpfen.dsl.printer`), the converters, builders and file parsers, and whatever else dsl-tools offers outside
     * its generated parsers. core-workspace reaches the readers through the public `DslReader` adapter of this package,
     * so it needs no adapter package of its own.
     */
    const val DSL_ADAPTER_PACKAGE: String = "io.karpfen.ide.core.document.dsl"

    /** The module directories of the core layer, which is UI-agnostic and compiles in explicit API mode. */
    val coreModules: List<String> get() = packagePrefixes.keys.filter { it.startsWith("core/") }

    /** The module directories of the UI layer, which is built with Compose. */
    val uiModules: List<String> get() = packagePrefixes.keys.filter { it.startsWith("ui/") }

    /** The module directories of the runtime layer, the client of the karpfen runtime. The layer compiles in explicit API mode. */
    val runtimeModules: List<String> get() = packagePrefixes.keys.filter { it.startsWith("runtime/") }

    /** Directories whose Kotlin files are build tooling, not product code. */
    private val nonProductModules = setOf("build-logic")

    /**
     * Every Kotlin source file (`src/<sourceSet>/...kt`) of this code base, build tooling included. Konsist already
     * skips hidden directories and build output. Build scripts (`*.gradle.kts`) and the checkouts of other
     * repositories in git submodules ([Submodules]) are filtered out here.
     */
    private val allSourceFiles: List<KoFileDeclaration> by lazy {
        Konsist.scopeFromProject().files.filter { file ->
            val projectPath = file.projectPath.replace('\\', '/')
            file.path.endsWith(".kt") && "/src/" in projectPath && !Submodules.contains(projectPath)
        }
    }

    /** Every Kotlin source file in the repository except build tooling, which the rules on product code leave out. */
    val sourceFiles: List<KoFileDeclaration> by lazy {
        allSourceFiles.filter { it.moduleDir !in nonProductModules }
    }

    /**
     * The Kotlin files of the test source sets of build-logic: build tooling, left out of the rules on product code,
     * but tests like those of the modules, so the rules on tests judge them too.
     */
    val buildToolingTestFiles: List<KoFileDeclaration> by lazy {
        allSourceFiles.filter { it.moduleDir in nonProductModules && it.sourceSetName != "main" }
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

/** The module directory relative to the repository root, with '/' separators, for example `core/core-text`. */
internal val KoFileDeclaration.moduleDir: String
    get() = moduleName.replace('\\', '/')

/** The declared package, or "" for the default package. */
internal val KoFileDeclaration.packageName: String
    get() = packagee?.name ?: ""

/** True if this package or import name equals [prefix] or lies below it. So `a.b` is within `a`, but `ab` is not. */
internal fun String.isWithin(prefix: String): Boolean = this == prefix || startsWith("$prefix.")

/** True if this name lies within any of [prefixes]. */
internal fun String.isWithinAny(prefixes: Collection<String>): Boolean = prefixes.any { isWithin(it) }
