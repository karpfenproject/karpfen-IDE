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
package io.karpfen.ide.ui.workbench.bridges.view

/** Test tags of the bridge form. */
public object BridgeFormTestTags {
    /** The form as a whole. */
    public const val FORM: String = "BridgeForm"

    /** The field with the name of the bridge. */
    public const val NAME: String = "BridgeForm.name"

    /** The Save button. */
    public const val SAVE: String = "BridgeForm.save"

    /** The Cancel button. */
    public const val CANCEL: String = "BridgeForm.cancel"

    /** The list of what keeps the form from being saved. */
    public const val PROBLEMS: String = "BridgeForm.problems"

    /** The button that adds an inbound route. */
    public const val ADD_INBOUND: String = "BridgeForm.inbound.add"

    /** The button that adds an outbound route. */
    public const val ADD_OUTBOUND: String = "BridgeForm.outbound.add"

    /** The chip that chooses the kind with the id [kind], such as `http`. */
    public fun kind(kind: String): String = "BridgeForm.kind:$kind"

    /** The field or the box of the setting with the key [key]. */
    public fun setting(key: String): String = "BridgeForm.setting:$key"

    /** The field named [field] of the inbound route at [index]: `from`, `event` or `number`. `remove` is its Remove button. */
    public fun inbound(index: Int, field: String): String = "BridgeForm.inbound:$index.$field"

    /** The field named [field] of the outbound route at [index]: `what`, `to` or `number`. `remove` is its Remove button. */
    public fun outbound(index: Int, field: String): String = "BridgeForm.outbound:$index.$field"

    /** The button that opens the pick list of the field with the tag [field]. */
    public fun pick(field: String): String = "$field.pick"

    /** The entry [option] in the pick list of the field with the tag [field]. */
    public fun option(field: String, option: String): String = "$field.pick:$option"

    /** The part of the payload field at [field] of the inbound route at [index]: `name`, `value` or its button `remove`. */
    public fun payload(index: Int, field: Int, part: String): String = "BridgeForm.inbound:$index.payload:$field.$part"
}
