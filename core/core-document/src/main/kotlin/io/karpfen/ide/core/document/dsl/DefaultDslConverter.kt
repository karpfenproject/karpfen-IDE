package io.karpfen.ide.core.document.dsl

import instance.Model
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextSnapshot
import meta.ClassType
import meta.Metamodel
import states.StateMachine

/** Calls the dsl-tools converters and turns their exceptions into located diagnostics. */
internal class DefaultDslConverter(private val locator: DslErrorLocator) : DslConverter {

    override fun kmeta(source: TextSnapshot, index: SyntaxIndex, baseTypes: List<ClassType>): Conversion<Metamodel> =
        TODO("WP-PARSE: KmetaDSLConverter.parseKmetaString(source.text, baseTypes)")

    override fun kmodel(source: TextSnapshot, index: SyntaxIndex, metamodel: Metamodel): Conversion<Model> =
        TODO("WP-PARSE: KmodelDSLConverter.parseKmodelString(source.text, metamodel)")

    override fun kstates(source: TextSnapshot, index: SyntaxIndex): Conversion<StateMachine> =
        TODO("WP-PARSE: KstatesDSLConverter.parseKstatesString(source.text)")
}
