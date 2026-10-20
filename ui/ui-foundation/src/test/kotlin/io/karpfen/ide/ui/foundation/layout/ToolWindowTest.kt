package io.karpfen.ide.ui.foundation.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.karpfen.ide.ui.foundation.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ToolWindowTest {

    @Test
    fun `a tool window shows its title as a heading, its actions and its content below the header`() = runComposeUiTest {
        var refreshed = 0
        setThemedContent {
            Box(Modifier.size(300.dp, 200.dp)) {
                ToolWindow(
                    title = "Problems",
                    actions = { Text("Refresh", Modifier.testTag("action").clickable { refreshed++ }) },
                ) {
                    Box(Modifier.fillMaxSize().testTag("content"))
                }
            }
        }

        onNodeWithText("Problems").assertIsDisplayed()
        onNodeWithText("Problems").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        onNodeWithTag("action").assertIsDisplayed().performClick()
        assertEquals(1, refreshed)

        val content = onNodeWithTag("content").getUnclippedBoundsInRoot()
        val header = ToolWindowDefaults.HeaderHeight + 1.dp
        assertTrue((content.top - header).value in -0.5f..0.5f, "content starts below the header: ${content.top}")
        assertTrue((content.height - (200.dp - header)).value in -0.5f..0.5f, "content fills the rest: ${content.height}")
        assertTrue((content.width - 300.dp).value in -0.5f..0.5f)
    }
}
