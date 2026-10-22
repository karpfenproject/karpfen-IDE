package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.workspace.environment.DescriptorFileRole

/**
 * The language a file in this role is written in: the metamodel and the events are KMeta, the model is KModel,
 * a state machine is KStates; null for a bridge, which may be any file. The one rule for the resolver's check of a
 * descriptor and for the converter, which converts a context's file only in the language of its role.
 */
internal val DescriptorFileRole.language: KarpfenLanguage?
    get() = when (this) {
        DescriptorFileRole.METAMODEL, DescriptorFileRole.EVENTS -> KarpfenLanguage.KMETA
        DescriptorFileRole.MODEL -> KarpfenLanguage.KMODEL
        DescriptorFileRole.STATE_MACHINE -> KarpfenLanguage.KSTATES
        DescriptorFileRole.BRIDGE -> null
    }

/** The descriptor key of this role, as findings name it. */
internal val DescriptorFileRole.key: String
    get() = when (this) {
        DescriptorFileRole.METAMODEL -> "metamodel"
        DescriptorFileRole.EVENTS -> "events"
        DescriptorFileRole.MODEL -> "model"
        DescriptorFileRole.STATE_MACHINE -> "stateMachines[].file"
        DescriptorFileRole.BRIDGE -> "bridges[]"
    }
