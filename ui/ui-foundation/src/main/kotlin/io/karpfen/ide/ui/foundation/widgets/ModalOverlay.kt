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
package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Lays [panel] in the middle of the whole area, over whatever was drawn before the overlay, on a dimmed layer that
 * keeps every click, drag and turn of the wheel away from what lies behind. The workbench shows its dialogs and the
 * form of a bridge this way.
 *
 * The dimmed layer is a sibling behind [panel] and not its parent, because a parent would break the clicks of the
 * panel's controls. Compose hands a pointer event first to the control it hits and then to the ancestors of that
 * control, and a button gives up its click when anyone consumes a move of the pointer between press and release. A
 * layer around the panel that consumed everything would cancel every click during which the mouse moved by a pixel.
 *
 * As a sibling the layer gets only the events that land outside the panel or on a part of it without a control. It
 * consumes all of them. Because Compose stops looking for a target at the first sibling that takes an event, nothing
 * behind the overlay sees them, and because they are consumed, no ancestor of the overlay acts on them either.
 */
@Composable
public fun ModalOverlay(panel: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(DIM).pointerInput(Unit) { consumeEverything() })
        panel()
    }
}

/** Consumes every pointer event that reaches the dimmed layer of [ModalOverlay], for as long as the layer is shown. */
private suspend fun PointerInputScope.consumeEverything() {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

/** The colour of the layer that dims what lies behind the panel of [ModalOverlay]. */
private val DIM = Color.Black.copy(alpha = 0.3f)
