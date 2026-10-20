package io.karpfen.ide.core.document.registry

import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.language.LanguageRegistry

/**
 * The registry of the built-in karpfen languages (KMeta, KModel, KStates). Construction only wires objects; it never
 * tokenizes or parses. [converter] is shared by the standalone semantics of KMeta and KStates.
 */
public fun defaultLanguageRegistry(converter: DslConverter = DslConverter()): LanguageRegistry =
    LanguageRegistry(
        listOf(
            KmetaLanguageSupport(converter),
            KmodelLanguageSupport(),
            KstatesLanguageSupport(converter),
        ),
    )
