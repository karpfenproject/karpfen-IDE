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

/** Test tags of the diagram view. */
public object DiagramViewTestTags {
    /** The view as a whole. */
    public const val VIEW: String = "Diagram.view"

    /** The canvas the diagram is drawn on. */
    public const val CANVAS: String = "Diagram.canvas"

    /** The note that the text has errors and the drawing is outdated. */
    public const val STALE: String = "Diagram.stale"

    /** The note that something in the view failed and the drawing may be incomplete. */
    public const val FAULT: String = "Diagram.fault"

    /** What the view tells the user to click while an action waits for a box. */
    public const val HINT: String = "Diagram.hint"

    /** The field a user types a name into. */
    public const val ASK: String = "Diagram.ask"

    /** What the view says while there is nothing to draw. */
    public const val EMPTY: String = "Diagram.empty"

    /** The button that makes the drawing smaller. */
    public const val ZOOM_OUT: String = "Diagram.zoomOut"

    /** The current zoom in percent. A click sets it back to 100%. */
    public const val ZOOM: String = "Diagram.zoom"

    /** The button that makes the drawing larger. */
    public const val ZOOM_IN: String = "Diagram.zoomIn"

    /** The button that fits the whole drawing into the view. */
    public const val FIT: String = "Diagram.fit"
}
