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
package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.workspace.context.treeOf
import io.karpfen.ide.core.workspace.context.ws
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the one rule for whether the files descriptors name are there: only files of the workspace tree count, those
 * of another language than their role are told apart, and completion's candidates are the files that fit a role.
 */
class DescriptorFilesTest {

    private val tree = treeOf(
        "ctx/robot.kenv.json", "ctx/robot.kmeta", "ctx/robot.kstates", "ctx/models/robot.kmodel", "ctx/mqtt.kbridge.json",
    )
    private val files = DescriptorFiles(tree)
    private val ctx = ws("ctx")

    /** The tree's file at [relative]. */
    private fun file(relative: String): WorkspaceNode.File = tree.find(ws(relative)) as WorkspaceNode.File

    @Test
    fun `a file of the tree that fits its role is present, also below and above the descriptor`() {
        assertEquals(NamedFile.Present(file("ctx/robot.kmeta")), files.lookUp(ctx, "robot.kmeta", DescriptorFileRole.METAMODEL))
        val model = files.lookUp(ctx, "./models/robot.kmodel", DescriptorFileRole.MODEL)
        assertEquals(NamedFile.Present(file("ctx/models/robot.kmodel")), model)
        val machine = files.lookUp(ws("ctx/models"), "../robot.kstates", DescriptorFileRole.STATE_MACHINE)
        assertEquals(NamedFile.Present(file("ctx/robot.kstates")), machine)
        val bridge = files.lookUp(ctx, "robot.kstates", DescriptorFileRole.BRIDGE)
        assertEquals(NamedFile.Present(file("ctx/robot.kstates")), bridge, "a bridge may be any file")
    }

    @Test
    fun `a file the tree lacks is missing, wherever it lies on disk`() {
        listOf("absent.kmeta", "build/robot.kmeta", "../../outside.kmeta", "models").forEach { value ->
            val expected = NamedFile.Missing(ctx.resolve(value).toAbsolutePath().normalize())
            assertEquals(expected, files.lookUp(ctx, value, DescriptorFileRole.METAMODEL), value)
        }
        assertEquals(null, files.lookUp(ctx, "absent.kmeta", DescriptorFileRole.METAMODEL).file)
        assertEquals(NamedFile.NoPath, files.lookUp(ctx, "bad\u0000name.kmeta", DescriptorFileRole.METAMODEL))
    }

    @Test
    fun `a file of another language than its role is told apart and still names the file`() {
        val named = files.lookUp(ctx, "robot.kstates", DescriptorFileRole.METAMODEL)

        assertEquals(NamedFile.WrongLanguage(file("ctx/robot.kstates"), KarpfenLanguage.KMETA), named)
        assertEquals(file("ctx/robot.kstates"), named.file)
    }

    @Test
    fun `the candidates of a role are the files of the tree that fit it`() {
        assertEquals(listOf(ws("ctx/robot.kmeta")), files.candidates(DescriptorFileRole.EVENTS).map { it.path })
        assertEquals(listOf(ws("ctx/models/robot.kmodel")), files.candidates(DescriptorFileRole.MODEL).map { it.path })
        assertEquals(tree.files(), files.candidates(DescriptorFileRole.BRIDGE))
    }
}
