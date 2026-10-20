package io.karpfen.ide.core.document.parse

import meta.Metamodel
import states.StateMachine

/**
 * A semantic model built by a dsl-tools converter from one file alone (spec §3.3, "standalone semantics").
 *
 * The wrapped dsl-tools classes are mutable; the IDE never mutates them (architecture proposal D1: every change is a
 * text edit). KModel files need a metamodel and are converted in core-workspace, so there is no KModel variant.
 */
public sealed interface DslModel {

    /** A converted `.kmeta` file. */
    public data class Kmeta(public val metamodel: Metamodel) : DslModel

    /** A converted `.kstates` file. */
    public data class Kstates(public val stateMachine: StateMachine) : DslModel
}
