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

/**
 * Identity of the `ui-diagram` module. The module draws the diagrams of `notation-diagram` on a canvas and handles
 * moving and zooming. Start reading at `StatechartPane`.
 */
public object UiDiagramModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "ui-diagram"
}
