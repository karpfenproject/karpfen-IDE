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
