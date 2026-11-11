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
package io.karpfen.ide.ui.workbench.problems.view

/** Test tags of the Problems view, [ProblemsView]. Its rows carry the tags `TreeViewTestTags.row(ProblemKey)`. */
public object ProblemsViewTestTags {
    /** The severity filter toggle [name], which is "errors", "warnings" or "infos". */
    public fun filter(name: String): String = "Workbench.problems.filter:$name"
}
