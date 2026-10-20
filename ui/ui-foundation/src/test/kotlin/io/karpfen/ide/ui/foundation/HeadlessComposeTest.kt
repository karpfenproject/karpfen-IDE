package io.karpfen.ide.ui.foundation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/**
 * Proves that Compose UI tests run headless: runComposeUiTest renders off-screen through Skia, without a window.
 * Gradle runs every test of a karpfen.compose module with java.awt.headless=true (see build-logic), so a pass in CI
 * means no display was needed. The test does not assert that property itself, so it also passes under an IDE runner.
 */
@OptIn(ExperimentalTestApi::class)
class HeadlessComposeTest {

    @Test
    fun `renders a composable and finds its text node without a display`() {
        runComposeUiTest {
            setContent {
                Text("karpfen IDE")
            }

            onNodeWithText("karpfen IDE").assertIsDisplayed()
        }
    }
}
