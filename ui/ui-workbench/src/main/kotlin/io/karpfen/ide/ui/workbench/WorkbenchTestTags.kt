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
package io.karpfen.ide.ui.workbench

import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * Test tags of the workbench layout, for the headless UI tests and the end-to-end tests. The feature views have tags of
 * their own next to them (`WelcomeTestTags`, `EditorAreaTestTags`, `ProblemsViewTestTags`, `WorkbenchDialogTestTags`),
 * so no view package depends on the root package.
 */
public object WorkbenchTestTags {
    /** The tool window [id] (its frame with header and content). */
    public fun toolWindow(id: ToolWindowId): String = "Workbench.toolWindow:${id.name}"
}
