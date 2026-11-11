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
package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The text of an environment descriptor as the workspace read it, with the diagnostics of the text itself. These are
 * its syntax errors and, for a text loaded from disk, the findings of loading it. The session of an open descriptor
 * computes and carries them itself, as `AnalyzedFile.ownDiagnostics` does for a karpfen file. So they are never among
 * the project diagnostics of the descriptor. The problems of a descriptor that is not open merge the two.
 *
 * @property snapshot the text the descriptor was read from. Every range of its diagnostics, own and project, refers to
 *   it. For an open descriptor it is the editor's snapshot with its lineage, so findings can be pushed into its
 *   session. Otherwise it is the normalized disk text.
 * @property ownDiagnostics the syntax errors and loading findings, sorted with [Diagnostic.ORDER].
 */
internal data class DescriptorSource(val snapshot: TextSnapshot, val ownDiagnostics: List<Diagnostic>)
