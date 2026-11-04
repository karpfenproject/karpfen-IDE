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

/** How an object of one snapshot differs from the other. */
public enum class DifferenceKind {
    /** The expected snapshot has the object, the actual one has not. */
    MISSING,

    /** The actual snapshot has the object, the expected one has not. */
    UNEXPECTED,

    /** Both have the object, with a different type, different properties or different relations. */
    DIFFERENT,
}

/**
 * One object that differs between two snapshots.
 *
 * @property id the object's id.
 * @property kind how it differs.
 * @property detail what differs, in a plain sentence.
 */
public data class ObjectDifference(public val id: String, public val kind: DifferenceKind, public val detail: String)

/**
 * Compares two snapshots object by object: the type, the properties by key and the relations by key and target,
 * keyed targets by id and anonymous targets by content, ignoring origin and revision. Used to compare the runtime's
 * read-back of a deployed model with the seed, and to tell what a snapshot changed in the mirror. Stateless.
 */
public object MirrorComparison {
    /**
     * The objects that differ between [expected] and [actual]: first those of [expected] in its order (missing or
     * different), then those only [actual] has, in its order.
     */
    public fun differences(expected: MirrorSnapshot, actual: MirrorSnapshot): List<ObjectDifference> {
        val found = ArrayList<ObjectDifference>()
        expected.objects.forEach { (id, wanted) ->
            val seen = actual.objects[id]
            if (seen == null) {
                found += ObjectDifference(id, DifferenceKind.MISSING, "The object '$id' of type '${wanted.typeName}' is missing.")
            } else {
                detail(wanted, seen)?.let { found += ObjectDifference(id, DifferenceKind.DIFFERENT, it) }
            }
        }
        actual.objects.forEach { (id, seen) ->
            if (id !in expected.objects) {
                found += ObjectDifference(id, DifferenceKind.UNEXPECTED, "The object '$id' of type '${seen.typeName}' is not expected.")
            }
        }
        return found
    }

    /** What differs between [wanted] and [seen], or null if their content is the same. */
    private fun detail(wanted: MirrorObject, seen: MirrorObject): String? {
        val parts = ArrayList<String>()
        if (wanted.typeName != seen.typeName) parts += "its type is '${seen.typeName}' instead of '${wanted.typeName}'"
        val wantedProperties = wanted.properties.associate { it.key to it.value }
        val seenProperties = seen.properties.associate { it.key to it.value }
        (wantedProperties.keys + seenProperties.keys).forEach { key ->
            val want = wantedProperties[key]
            val have = seenProperties[key]
            when {
                want == have -> Unit
                have == null -> parts += "the property '$key' is missing"
                want == null -> parts += "the property '$key' is not expected"
                else -> parts += "the property '$key' is ${describe(have)} instead of ${describe(want)}"
            }
        }
        val wantedRelations = wanted.relations.associate { it.key to targets(it) }
        val seenRelations = seen.relations.associate { it.key to targets(it) }
        (wantedRelations.keys + seenRelations.keys).forEach { key ->
            val want = wantedRelations[key]
            val have = seenRelations[key]
            when {
                want == have -> Unit
                have == null -> parts += "the relation '$key' is missing"
                want == null -> parts += "the relation '$key' is not expected"
                else -> parts += "the relation '$key' holds ${describeTargets(have)} instead of ${describeTargets(want)}"
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "In '${wanted.id}', ", postfix = ".")
    }

    /** The targets of [relation] for comparison: anonymous targets by content. */
    private fun targets(relation: MirrorRelation): List<ObjectRef> =
        relation.targets.map { target ->
            if (target is ObjectRef.Anonymous) ObjectRef.Anonymous(MirrorContent.normalized(target.value)) else target
        }

    /** A property value in a sentence. */
    private fun describe(value: PropertyValue): String =
        when (value) {
            is PropertyValue.Single -> describe(value.value)
            is PropertyValue.Many -> value.values.joinToString(", ", "[", "]") { describe(it) }
        }

    /** A scalar in a sentence. */
    private fun describe(value: ScalarValue): String =
        when (value) {
            is ScalarValue.Number -> value.value.toString()
            is ScalarValue.Text -> "\"${value.value}\""
            is ScalarValue.Flag -> value.value.toString()
        }

    /** Relation targets in a sentence. */
    private fun describeTargets(targets: List<ObjectRef>): String =
        targets.joinToString(", ", "[", "]") { target ->
            when (target) {
                is ObjectRef.Keyed -> "'${target.id}'"
                is ObjectRef.Anonymous -> "an anonymous '${target.value.typeName}'"
                ObjectRef.Missing -> "nothing"
            }
        }
}
