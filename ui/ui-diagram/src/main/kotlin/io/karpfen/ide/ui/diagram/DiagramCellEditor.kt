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
package io.karpfen.ide.ui.diagram

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The editor that an opened code cell of a diagram holds. The diagram module does not know the code editor of the
 * IDE, so whoever shows a diagram hands one in. Without one, a cell falls back to a plain text field.
 */
public fun interface DiagramCellEditor {
    /**
     * Shows [code], the code of a cell as it stands when the cell opens, to be edited. Every change of the text goes
     * to [onCode] with the whole new text. [modifier] gives the editor its place in the cell.
     */
    @Composable
    public fun Editor(code: String, onCode: (String) -> Unit, modifier: Modifier)
}
