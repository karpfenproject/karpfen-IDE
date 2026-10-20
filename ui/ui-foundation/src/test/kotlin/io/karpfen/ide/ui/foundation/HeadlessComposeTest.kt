package io.karpfen.ide.ui.foundation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertTrue

/** Proves that Compose UI tests run headless (off-screen Skia rendering, no window, no display). */
@OptIn(ExperimentalTestApi::class)
class HeadlessComposeTest {

    @Test
    fun `renders a composable and finds its text node without a display`() {
        assertTrue(GraphicsEnvironment.isHeadless(), "Compose UI tests are expected to run with java.awt.headless=true")

        runComposeUiTest {
            setContent {
                Text("karpfen IDE")
            }

            onNodeWithText("karpfen IDE").assertIsDisplayed()
        }
    }
}
