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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import java.nio.file.Path

/** What a problems view shows: the diagnostics per file, without snapshots and open flags. */
internal fun WorkspaceProblems.byFile(): Map<Path, List<Diagnostic>> = files.associate { it.path to it.diagnostics }
