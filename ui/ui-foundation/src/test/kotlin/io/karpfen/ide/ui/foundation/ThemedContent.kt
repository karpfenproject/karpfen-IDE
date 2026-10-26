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
package io.karpfen.ide.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme

/** Sets [content] wrapped in the light (or dark) [KarpfenTheme], which every foundation widget needs. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setThemedContent(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    setContent { KarpfenTheme(darkTheme = darkTheme, content = content) }
}
