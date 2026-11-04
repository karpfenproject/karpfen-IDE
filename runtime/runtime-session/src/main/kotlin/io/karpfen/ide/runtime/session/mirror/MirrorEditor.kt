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

import io.karpfen.dsl.meta.AssociationType

/**
 * A working copy of a [MirrorSnapshot] for one step of the [MirrorReducer]: objects are put, embedded subtrees removed
 * and references to removed objects scrubbed, then [build] answers the new snapshot and what changed. Confined to the
 * step that created it; the snapshot it started from stays as it was.
 *
 * @param before the snapshot the step starts from.
 */
internal class MirrorEditor(private val before: MirrorSnapshot) {
    /** The objects as edited so far, in the order of [before] with new objects appended. */
    private val objects = LinkedHashMap(before.objects)

    /** The containment as edited so far. */
    private val containment = LinkedHashMap(before.containment)

    /** The roots as edited so far. */
    private var roots = before.roots

    /** The ids removed so far. */
    private val removed = LinkedHashSet<String>()

    /** The ids whose objects were put or touched by scrubbing. */
    private val touched = LinkedHashSet<String>()

    /** The object [id] as edited so far, or null if there is none. */
    fun objectOf(id: String): MirrorObject? = objects[id]

    /** Puts [obj] under its id, replacing the object of that id as a whole. */
    fun put(obj: MirrorObject) {
        objects[obj.id] = obj
        removed.remove(obj.id)
        touched += obj.id
    }

    /** Records that the keyed object [id] is held as [holder]. */
    fun contain(id: String, holder: Containment) {
        containment[id] = holder
    }

    /**
     * Removes the object [id] with every keyed object it embeds, directly or through anonymous objects, and answers the
     * removed ids; objects that are no longer there are skipped.
     */
    fun removeSubtree(id: String): Set<String> {
        val gone = LinkedHashSet<String>()
        val pending = ArrayDeque(listOf(id))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            val obj = objects.remove(next) ?: continue
            gone += next
            containment.remove(next)
            pending += embeddedKeyedChildren(obj)
        }
        removed += gone
        touched -= gone
        roots = roots.filterNot { it is ObjectRef.Keyed && it.id in gone }
        return gone
    }

    /**
     * Removes every reference to the removed objects from the relations of the remaining objects, anonymous ones
     * included, and stamps each object it changes with [revision]. An atomic relation that referred to a removed object
     * becomes empty; a list relation loses the entry.
     */
    fun scrub(revision: Long) {
        if (removed.isEmpty()) return
        objects.entries.forEach { entry ->
            val scrubbed = scrubbed(entry.value)
            if (scrubbed != entry.value) {
                entry.setValue(scrubbed.copy(revision = revision))
                touched += entry.key
            }
        }
        roots = roots.map { root -> if (root is ObjectRef.Anonymous) ObjectRef.Anonymous(scrubbed(root.value)) else root }
    }

    /** The snapshot as edited and the change against the snapshot the editor started from. */
    fun build(): Pair<MirrorSnapshot, MirrorChange> {
        val created = touched.filterTo(LinkedHashSet()) { it !in before.objects }
        val changed = touched.filterTo(LinkedHashSet()) { id ->
            val old = before.objects[id]
            val new = objects[id]
            old != null && new != null && !MirrorContent.same(old, new)
        }
        val deleted = removed.filterTo(LinkedHashSet()) { it in before.objects }
        return MirrorSnapshot(LinkedHashMap(objects), roots, LinkedHashMap(containment)) to MirrorChange(changed, created, deleted)
    }

    /** [obj] without references to removed objects. */
    private fun scrubbed(obj: MirrorObject): MirrorObject {
        val relations = obj.relations.map { relation ->
            val targets = relation.targets.mapNotNull { target ->
                when (target) {
                    is ObjectRef.Keyed -> target.takeUnless { it.id in removed }
                    is ObjectRef.Anonymous -> ObjectRef.Anonymous(scrubbed(target.value))
                    ObjectRef.Missing -> target
                }
            }
            if (targets == relation.targets) relation else relation.copy(targets = targets)
        }
        return if (relations == obj.relations) obj else obj.copy(relations = relations)
    }

    companion object {
        /** The ids of the keyed objects [obj] embeds through `has` relations, directly or through anonymous objects. */
        fun embeddedKeyedChildren(obj: MirrorObject): Set<String> {
            val ids = LinkedHashSet<String>()
            obj.relations.filter { it.association == AssociationType.EMBEDDED }.forEach { relation ->
                relation.targets.forEach { target ->
                    when (target) {
                        is ObjectRef.Keyed -> ids += target.id
                        is ObjectRef.Anonymous -> ids += embeddedKeyedChildren(target.value)
                        ObjectRef.Missing -> Unit
                    }
                }
            }
            return ids
        }
    }
}

/** Compares mirror objects by content: type, properties and relations, ignoring origin and revision. Stateless. */
internal object MirrorContent {
    /** True if [a] and [b] have the same id, type, properties and relations, anonymous targets compared alike. */
    fun same(a: MirrorObject, b: MirrorObject): Boolean = normalized(a) == normalized(b)

    /** [obj] with origin and revision cleared, also in its anonymous targets. */
    fun normalized(obj: MirrorObject): MirrorObject =
        obj.copy(
            origin = ObjectOrigin.MODEL,
            revision = 0,
            relations = obj.relations.map { relation ->
                relation.copy(
                    targets = relation.targets.map { target ->
                        if (target is ObjectRef.Anonymous) ObjectRef.Anonymous(normalized(target.value)) else target
                    },
                )
            },
        )
}
