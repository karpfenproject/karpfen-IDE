package io.karpfen.ide.ui.foundation.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class TreePointerPolicyTest {

    private val rows = TreeRows.flatten(
        listOf(TreeNode("dir", "dir", children = listOf(TreeNode("dir/file", "file"))), TreeNode("other", "other")),
        expanded = setOf("dir"),
    )
    private val dir = rows[0]
    private val file = rows[1]
    private val other = rows[2]

    private val policy = TreePointerPolicy(doubleClickTimeoutMillis = 300)

    private fun press(row: TreeRow<String>, x: Float = 100f, at: Long) =
        policy.primaryPress(row, x, at, chevronStart = 4f, chevronEnd = 20f)

    /** The intents of a single click on the row [key]: select it and report the click. */
    private fun click(key: String): List<TreeIntent<String>> = listOf(TreeIntent.Select(key), TreeIntent.Click(key))

    @Test
    fun `a press selects its row and is a click`() {
        assertEquals(click("dir/file"), press(file, at = 1_000))
    }

    @Test
    fun `a second press on the same row within the timeout selects, clicks and opens`() {
        press(file, at = 1_000)

        assertEquals(click("dir/file") + TreeIntent.Open("dir/file"), press(file, at = 1_250))
    }

    @Test
    fun `a third press starts a new click sequence after the double click`() {
        press(file, at = 1_000)
        press(file, at = 1_100)

        assertEquals(click("dir/file"), press(file, at = 1_200))
    }

    @Test
    fun `slow presses or presses on another row are single clicks`() {
        press(file, at = 1_000)
        assertEquals(click("dir/file"), press(file, at = 1_301))

        press(file, at = 2_000)
        assertEquals(click("other"), press(other, at = 2_100))
    }

    @Test
    fun `a press on the chevron of an expandable row toggles it without selecting`() {
        assertEquals(listOf(TreeIntent.Toggle("dir")), press(dir, x = 4f, at = 1_000))
        assertEquals(listOf(TreeIntent.Toggle("dir")), press(dir, x = 19.9f, at = 1_100), "a quick second toggle is no double click")
        assertEquals(click("dir"), press(dir, x = 20f, at = 1_200), "right of the chevron")
        assertEquals(click("dir"), press(dir, x = 3f, at = 2_000), "left of the chevron")
    }

    @Test
    fun `a leaf has no chevron`() {
        assertEquals(click("dir/file"), press(file, x = 10f, at = 1_000))
    }
}
