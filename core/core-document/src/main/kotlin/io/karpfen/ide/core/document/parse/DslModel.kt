package io.karpfen.ide.core.document.parse

import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine

/**
 * A semantic model built by a dsl-tools converter from one file alone (the "standalone semantics" of a document).
 *
 * The wrapped dsl-tools classes are mutable; the IDE never mutates them, because text files are the single source of
 * truth and every change is a text edit. KModel files need a metamodel and are converted in core-workspace, so there is no KModel variant.
 */
public sealed interface DslModel {

    /**
     * A converted `.kmeta` file.
     *
     * @property metamodel the dsl-tools metamodel.
     */
    public data class Kmeta(public val metamodel: Metamodel) : DslModel

    /**
     * A converted `.kstates` file.
     *
     * @property stateMachine the dsl-tools state machine.
     */
    public data class Kstates(public val stateMachine: StateMachine) : DslModel
}
