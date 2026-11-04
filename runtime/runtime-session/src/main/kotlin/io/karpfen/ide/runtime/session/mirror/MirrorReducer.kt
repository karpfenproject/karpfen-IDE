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
package io.karpfen.ide.runtime.session.mirror

import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.session.schema.TypeSchema

/**
 * Applies what the runtime reports to the mirror: a whole snapshot, an `objectChanged` push or an `objectDeleted` push.
 * Pure: every function answers a new [MirrorStep] and leaves the given snapshot as it is, so the reducer can be shared.
 *
 * - [replace] builds the whole mirror from a snapshot's roots.
 * - [changed] upserts every keyed object of the pushed tree as a whole (properties and relations replaced), not only
 *   the pushed one: the runtime serializes the whole subtree, `knows` targets included, from the live model when the
 *   block commits, so nested values are as current as the pushed object. An object that appears twice in one push is
 *   taken at its first occurrence. Every upserted object gets the push's revision.
 * - Removal by absence: for every upserted object, every keyed object it embedded through `has` before and no longer
 *   embeds, and that appears nowhere else in the same push, is removed with its embedded subtree, and every reference
 *   to a removed id is scrubbed. This is sound because the runtime only puts new objects into `has` relations, so a
 *   child never moves between owners; it also covers removals the runtime does not report with an `objectDeleted`.
 * - [deleted] removes the object with its embedded subtree, detaches it from its owner and scrubs the references to
 *   it; an unknown id changes nothing.
 *
 * @param schema the schema of the domain metamodel.
 * @param modelObjectIds the ids the deployed model declares; other ids are objects the runtime created.
 */
public class MirrorReducer(schema: TypeSchema, modelObjectIds: Set<String>) {
    /** Reads the runtime's object trees. */
    private val interpreter = WireObjectInterpreter(schema, modelObjectIds.toSet())

    /**
     * The mirror of a snapshot whose [roots] are the model's root objects at [revision]. Its change lists every object
     * as created; a caller that keeps an older mirror compares the two with [MirrorComparison].
     */
    public fun replace(roots: List<WireValue.WireObject>, revision: Long): MirrorStep {
        val objects = LinkedHashMap<String, MirrorObject>()
        val containment = LinkedHashMap<String, Containment>()
        val anomalies = ArrayList<String>()
        val rootRefs = roots.map { root ->
            val tree = interpreter.interpret(root, revision)
            tree.objects.forEach { (id, obj) -> objects.putIfAbsent(id, obj) }
            tree.containment.forEach { (id, holder) -> containment.putIfAbsent(id, holder) }
            anomalies += tree.anomalies
            tree.root
        }
        val snapshot = MirrorSnapshot(objects, rootRefs, containment)
        return MirrorStep(snapshot, MirrorChange(emptySet(), objects.keys.toSet(), emptySet()), anomalies)
    }

    /** [snapshot] after the `objectChanged` [push]. */
    public fun changed(snapshot: MirrorSnapshot, push: RuntimePush.ObjectChanged): MirrorStep {
        val tree = interpreter.interpret(push.value, push.revision)
        val anomalies = ArrayList(tree.anomalies)
        val root = tree.root
        if (root !is ObjectRef.Keyed) {
            anomalies += "The object pushed as '${push.objectId}' has no id; the push is ignored."
            return MirrorStep(snapshot, MirrorChange.NONE, anomalies)
        }
        if (root.id !in snapshot.objects) {
            anomalies += "The pushed object '${root.id}' is not in the mirror and no owner of it was pushed; it is added without owner."
        }
        val editor = MirrorEditor(snapshot)
        val pushed = tree.objects.keys
        tree.objects.values.forEach { obj ->
            val before = editor.objectOf(obj.id)?.let(MirrorEditor::embeddedKeyedChildren).orEmpty()
            editor.put(obj)
            val after = MirrorEditor.embeddedKeyedChildren(obj)
            before.filter { it !in after && it !in pushed }.forEach { editor.removeSubtree(it) }
        }
        tree.containment.forEach { (id, holder) -> editor.contain(id, holder) }
        editor.scrub(push.revision)
        val (next, change) = editor.build()
        return MirrorStep(next, change, anomalies)
    }

    /** [snapshot] after the `objectDeleted` [push]; an id the mirror does not hold changes nothing. */
    public fun deleted(snapshot: MirrorSnapshot, push: RuntimePush.ObjectDeleted): MirrorStep {
        if (push.objectId !in snapshot.objects) return MirrorStep(snapshot, MirrorChange.NONE, emptyList())
        val editor = MirrorEditor(snapshot)
        editor.removeSubtree(push.objectId)
        editor.scrub(push.revision)
        val (next, change) = editor.build()
        return MirrorStep(next, change, emptyList())
    }
}
