package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.language.LanguageRegistry
import kotlin.test.Test
import kotlin.test.assertFalse

/** The collaborators of the workbench: one source per fact (decisions 46 and 47 of the work-package plan). */
class WorkbenchServicesTest {

    @Test
    fun `the services hold no language registry of their own beside the workspace factory's`() {
        val type = WorkbenchServices::class.java
        val held = type.constructors.flatMap { it.parameterTypes.toList() } + type.declaredFields.map { it.type }
        assertFalse(
            LanguageRegistry::class.java in held,
            "a workspace's languages come from its factory (Workspace.languages); a second registry could disagree",
        )
    }
}
