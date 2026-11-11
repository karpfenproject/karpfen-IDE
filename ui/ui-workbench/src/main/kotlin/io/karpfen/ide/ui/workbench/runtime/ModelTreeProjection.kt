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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.runtime.session.mirror.MirrorObject
import io.karpfen.ide.runtime.session.mirror.MirrorProperty
import io.karpfen.ide.runtime.session.mirror.MirrorRelation
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.ObjectOrigin
import io.karpfen.ide.runtime.session.mirror.ObjectRef
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import kotlin.math.abs

/** One row of the model tree as the Model tab lists it, indented by [depth]. Immutable. */
public sealed interface ModelTreeRow {
    /** Identifies the row across updates of the model. */
    public val key: String

    /** How deep the row is nested, 0 for the top level. */
    public val depth: Int

    /**
     * An object of the model.
     *
     * @property id the object's id, empty for an object without one.
     * @property typeName the object's type.
     * @property relation the relation of the owner that holds the object, or null at the top level.
     * @property runtimeCreated true for an object the runtime created while the environment ran.
     * @property expandable true if the object has properties or relations to show.
     * @property expanded true if they are shown.
     * @property selected true for the selected object.
     */
    public data class Object(
        override val key: String,
        override val depth: Int,
        public val id: String,
        public val typeName: String,
        public val relation: String?,
        public val runtimeCreated: Boolean,
        public val expandable: Boolean,
        public val expanded: Boolean,
        public val selected: Boolean,
    ) : ModelTreeRow

    /**
     * A simple property of an object.
     *
     * @property name the property's key.
     * @property value the value in words.
     */
    public data class Property(override val key: String, override val depth: Int, public val name: String, public val value: String) :
        ModelTreeRow

    /**
     * A relation whose targets are held elsewhere, shown as links to their ids.
     *
     * @property relation the relation's key.
     * @property targets the ids of the targets in order. An entry that refers to no keyed object has an empty id.
     */
    public data class Link(
        override val key: String,
        override val depth: Int,
        public val relation: String,
        public val targets: List<String>,
    ) : ModelTreeRow
}

/**
 * Projects the mirror of an observed model to the rows of its tree. It is pure.
 *
 * The roots come first, then the objects that no root holds. While an object is expanded, its rows follow it. They are
 * its simple properties, its links and the objects it holds through containment. A keyed object is held by the owner
 * that the mirror's containment names. An anonymous object is always held by the object that refers to it. Every other
 * target of a relation is a link, including an entry that refers to no object. Each keyed object shows once, even if
 * it is reachable in several ways.
 */
internal object ModelTreeProjection {

    /** The rows of [mirror] with the objects of [expanded] open and [selected] selected. */
    fun rows(mirror: MirrorSnapshot, expanded: Set<String>, selected: String?): List<ModelTreeRow> =
        Builder(mirror, expanded, selected).build()

    /** The key of the object row of the object [id]. */
    fun keyOf(id: String): String = "$OBJECT$id"

    /**
     * The keys of the objects that hold the object [id], outermost first. It follows the mirror's containment and stops
     * at a cycle.
     */
    fun ownersOf(mirror: MirrorSnapshot, id: String): List<String> {
        val owners = ArrayList<String>()
        var current = mirror.containment[id]
        while (current != null && keyOf(current.owner) !in owners) {
            owners.add(0, keyOf(current.owner))
            current = mirror.containment[current.owner]
        }
        return owners
    }

    /** The words for [value]. Numbers have no needless fraction, texts are in quotes and lists are in brackets. */
    fun text(value: PropertyValue): String = when (value) {
        is PropertyValue.Single -> text(value.value)
        is PropertyValue.Many -> value.values.joinToString(", ", "[", "]") { text(it) }
    }

    /** The words for [value]. */
    private fun text(value: ScalarValue): String = when (value) {
        is ScalarValue.Number -> number(value.value)
        is ScalarValue.Text -> "\"${value.value}\""
        is ScalarValue.Flag -> value.value.toString()
    }

    /** The words for [number]. A whole number below 1e15 in magnitude is written without its fraction. */
    private fun number(number: Double): String =
        if (number % 1.0 == 0.0 && abs(number) < WHOLE) number.toLong().toString() else number.toString()

    /** Collects the rows of one projection. */
    private class Builder(private val mirror: MirrorSnapshot, private val expanded: Set<String>, private val selected: String?) {
        private val rows = ArrayList<ModelTreeRow>()
        private val shown = HashSet<String>()

        fun build(): List<ModelTreeRow> {
            mirror.roots.forEachIndexed { index, root -> top(root, "$ROOT$index") }
            val held = mirror.containment.keys
            mirror.objects.values.filter { it.id !in shown && it.id !in held }.forEach { add(it, keyOf(it.id), 0, null) }
            return rows
        }

        private fun top(root: ObjectRef, anonymousKey: String) {
            when (root) {
                is ObjectRef.Keyed -> mirror.objects[root.id]?.let { add(it, keyOf(it.id), 0, null) }
                is ObjectRef.Anonymous -> add(root.value, anonymousKey, 0, null)
                ObjectRef.Missing -> Unit
            }
        }

        private fun add(item: MirrorObject, key: String, depth: Int, relation: String?) {
            if (item.id.isNotEmpty() && !shown.add(item.id)) return
            val open = key in expanded
            rows += ModelTreeRow.Object(
                key, depth, item.id, item.typeName, relation,
                runtimeCreated = item.origin == ObjectOrigin.RUNTIME,
                expandable = item.properties.isNotEmpty() || item.relations.isNotEmpty(),
                expanded = open,
                selected = key == selected,
            )
            if (open) children(item, key, depth + 1)
        }

        private fun children(item: MirrorObject, key: String, depth: Int) {
            item.properties.forEach { rows += property(it, key, depth) }
            item.relations.forEach { relation(item, it, key, depth) }
        }

        private fun property(property: MirrorProperty, owner: String, depth: Int): ModelTreeRow =
            ModelTreeRow.Property("$PROPERTY$owner/${property.key}", depth, property.key, text(property.value))

        private fun relation(owner: MirrorObject, relation: MirrorRelation, key: String, depth: Int) {
            val held = relation.targets.filter { holds(owner, relation, it) }
            val links = relation.targets.filterNot { holds(owner, relation, it) }
            if (links.isNotEmpty()) {
                val targets = links.map { (it as? ObjectRef.Keyed)?.id.orEmpty() }
                rows += ModelTreeRow.Link("$LINK$key/${relation.key}", depth, relation.key, targets)
            }
            held.forEachIndexed { index, target ->
                when (target) {
                    is ObjectRef.Keyed -> mirror.objects[target.id]?.let { add(it, keyOf(it.id), depth, relation.key) }
                    is ObjectRef.Anonymous -> add(target.value, "$key/${relation.key}#$index", depth, relation.key)
                    ObjectRef.Missing -> Unit
                }
            }
        }

        private fun holds(owner: MirrorObject, relation: MirrorRelation, target: ObjectRef): Boolean = when (target) {
            is ObjectRef.Anonymous -> true
            is ObjectRef.Keyed -> mirror.containment[target.id].let { it != null && it.owner == owner.id && it.relation == relation.key }
            ObjectRef.Missing -> false
        }
    }

    private const val OBJECT = "o:"
    private const val ROOT = "r:"
    private const val PROPERTY = "p:"
    private const val LINK = "l:"
    private const val WHOLE = 1e15
}
