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
package io.karpfen.ide.core.workspace

/**
 * Identity of the `core-workspace` module. The module holds the project model: the file tree, the open documents, the
 * environment descriptors, the modeling contexts and the project analysis. It builds on `core-document`. The language
 * services, the runtime session and the diagram notation build on it. Start reading at
 * [Workspace].
 */
public object CoreWorkspaceModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "core-workspace"
}
