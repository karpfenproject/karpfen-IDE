package io.karpfen.ide.ui.workbench.problems.view

/** Test tags of the Problems view ([ProblemsView]); its rows carry `TreeViewTestTags.row(ProblemKey)`. */
public object ProblemsViewTestTags {
    /** The severity filter toggle [name] ("errors", "warnings", "infos"). */
    public fun filter(name: String): String = "Workbench.problems.filter:$name"
}
