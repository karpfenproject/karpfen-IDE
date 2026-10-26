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

/**
 * The one rule by which the architecture and documentation rules tell the directories of this code base from what they
 * leave out: build output ([BuildOutput]) and the checkouts of other repositories in git submodules ([Submodules]),
 * whether or not such a checkout holds a Gradle build of its own. Hidden directories and separate builds such as
 * build-logic are left out or kept by each rule as it needs.
 */
internal object OwnCode {

    /**
     * True for the directory at [relativePath], relative to the repository root with '/' separators, if it belongs to
     * this code base: neither build output nor a submodule checkout or a directory inside one.
     */
    fun isOwnDirectory(relativePath: String): Boolean =
        !BuildOutput.isBuildDirectory(relativePath) && !Submodules.contains(relativePath)
}
