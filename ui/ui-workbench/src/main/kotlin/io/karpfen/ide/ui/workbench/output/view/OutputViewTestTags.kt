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
package io.karpfen.ide.ui.workbench.output.view

/** Test tags of the Language Tools Output view ([OutputView]). */
public object OutputViewTestTags {
    /** The list of lines. */
    public const val LINES: String = "Workbench.output.lines"

    /** The scrollbar of the list of lines. */
    public const val SCROLLBAR: String = "Workbench.output.scrollbar"

    /** The toggle that keeps the newest line in sight. */
    public const val FOLLOW: String = "Workbench.output.follow"

    /** The button that clears the output. */
    public const val CLEAR: String = "Workbench.output.clear"

    /** The line with the sequence number [sequence]. */
    public fun line(sequence: Long): String = "Workbench.output.line:$sequence"
}
