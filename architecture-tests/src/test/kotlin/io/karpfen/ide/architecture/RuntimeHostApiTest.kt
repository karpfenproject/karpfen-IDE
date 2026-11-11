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

import com.lemonappdev.konsist.api.declaration.KoBaseDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.provider.modifier.KoVisibilityModifierProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * runtime-host keeps its whole API in its root package. That API is the host, its settings, statuses, problems and log
 * lines, and the factory of the default hosts. Every top-level declaration of the module states its visibility, as
 * explicit API mode demands. The declarations of its sub-packages are internal or private. The sub-packages hold the
 * bundled sources, the Docker names, and the Docker engine with its adapter. So no Docker type and nothing of the inner
 * workings of the hosts becomes public API.
 */
class RuntimeHostApiTest {

    /** The production files of runtime-host. */
    private val files: List<KoFileDeclaration> by lazy { KarpfenModules.mainFiles.filter { it.moduleDir == MODULE } }

    @Test
    fun `every top-level declaration of runtime-host states its visibility`() {
        assertTrue(files.isNotEmpty(), "no production code in $MODULE; is the Konsist scope broken?")

        val implicit = files.flatMap { file ->
            topLevel(file).filter { declaration ->
                !declaration.hasPublicModifier && !declaration.hasInternalModifier && !declaration.hasPrivateModifier
            }.map { "${file.packageName}.${nameOf(it)}" }
        }

        assertEquals(emptyList(), implicit)
    }

    @Test
    fun `only the root package of runtime-host declares public API`() {
        val subPackages = files.filter { it.packageName != ROOT }
        assertTrue(subPackages.isNotEmpty(), "runtime-host has no sub-packages; this check would be vacuous")

        val public = subPackages.flatMap { file ->
            topLevel(file).filter { it.hasPublicOrDefaultModifier }.map { "${file.packageName}.${nameOf(it)}" }
        }

        assertEquals(emptyList(), public, "only $ROOT may declare public API; the rest of runtime-host is internal")
    }

    /** The top-level declarations of [file] that have a visibility. */
    private fun topLevel(file: KoFileDeclaration): List<KoVisibilityModifierProvider> =
        file.declarations(includeNested = false, includeLocal = false).filterIsInstance<KoVisibilityModifierProvider>()

    /** The name of [declaration], for the failure message. */
    private fun nameOf(declaration: KoVisibilityModifierProvider): String =
        (declaration as? KoNameProvider)?.name ?: (declaration as KoBaseDeclaration).toString()

    private companion object {
        /** The module directory of runtime-host. */
        const val MODULE = "runtime/runtime-host"

        /** The root package of runtime-host, which holds its API. */
        const val ROOT = "io.karpfen.ide.runtime.host"
    }
}
