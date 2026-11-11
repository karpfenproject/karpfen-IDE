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
import java.io.File

/**
 * The files at the root of the repository that the architecture tests read besides the sources and documents, by
 * name. The build script of the architecture tests declares each of them as an input of their test task, one by one,
 * so that a change to one of them runs the tests again. `PackageLayoutTest` checks that the build script does so.
 */
internal object RootFiles {

    /** The ignore rules of git, which `PackageLayoutTest` checks against the build output. */
    const val GITIGNORE: String = ".gitignore"

    /** The git submodules, which `SubmodulesTest` checks against the directories the build hands over. */
    const val GITMODULES: String = ".gitmodules"

    /** Every file of this object. */
    val all: List<String> = listOf(GITIGNORE, GITMODULES)

    /** The text of the root file [name]. */
    fun read(name: String): String = File(Konsist.projectRootPath, name).readText()
}
