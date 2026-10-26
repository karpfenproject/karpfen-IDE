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
package io.karpfen.ide.ui.workbench.welcome.view

/** Test tags of the empty state ([WelcomeView]). */
public object WelcomeTestTags {
    /** The empty state shown without a folder. */
    public const val VIEW: String = "Workbench.welcome"

    /** The "Open Folder…" button. */
    public const val OPEN_FOLDER: String = "Workbench.welcome.openFolder"

    /** The entry of the recent [folder]. */
    public fun recentFolder(folder: String): String = "Workbench.welcome.recent:$folder"
}
