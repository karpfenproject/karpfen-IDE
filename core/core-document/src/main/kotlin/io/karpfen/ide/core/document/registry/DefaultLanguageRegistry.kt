package io.karpfen.ide.core.document.registry

import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.language.LanguageRegistry

/**
 * The registry of the built-in karpfen languages (KMeta, KModel, KStates). Construction only wires objects; it never
 * tokenizes or parses. [converter] is shared by the standalone semantics of KMeta and KStates.
 *
 * The three supports are the concrete factories of the [io.karpfen.ide.core.document.language.LanguageSupport] product
 * family. Each instantiates its language's tokenizer (package `antlr.lex`) and composes its parser from the ANTLR
 * syntax analyzer (`antlr.tree`) and the dsl-tools standalone semantics (`dsl`). A fourth language is a new support
 * class in this package plus one entry here; no existing language class changes.
 */
public fun defaultLanguageRegistry(converter: DslConverter = DslConverter()): LanguageRegistry =
    LanguageRegistry(
        listOf(
            KmetaLanguageSupport(converter),
            KmodelLanguageSupport(),
            KstatesLanguageSupport(converter),
        ),
    )
