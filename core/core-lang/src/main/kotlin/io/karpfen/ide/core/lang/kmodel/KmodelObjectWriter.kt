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
package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.lang.symbols.ContextTypes
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import java.nio.file.Path

/**
 * An object written as KModel text, without placeholders.
 *
 * @property key the key the object got, which differs from the key asked for where that one is taken.
 * @property text the text from `make object` to the closing brace of the object. Its first line has no indentation,
 *   and each further line is indented as it stands relative to the first one, four spaces per level.
 */
public data class KmodelObjectText(public val key: String, public val text: String)

/**
 * Writes whole objects of the types of one metamodel as plain KModel text, for those who write a model without the
 * text editor, such as the object diagram. The text is the one the completion of the text editor inserts for the same
 * type, because both are written by [KmodelObjectTemplates]: every property with the default of its type, every `has`
 * relation with the object of its target type and that type's members down to the depth limit, every `knows` relation
 * with an empty target, and one statement for a list member. The keys of the objects avoid the keys a model uses
 * already.
 *
 * The writer is an immutable value over [types]. It holds no workspace and reads nothing when it writes, so the object
 * diagram can keep it as long as the analysis it was made from is the latest one.
 *
 * @param types the types whose objects can be written, in the order of their metamodel. In a modeling context these
 *   are the types of its domain metamodel, all of which a model can instantiate, since KMeta knows no abstract types.
 *   Event types are no domain types and are not among them.
 */
public class KmodelObjectWriter(types: List<TypeInfo>) {

    /** The types of the writer, each name once, the first one of a name winning. */
    public val types: List<TypeInfo> = types.distinctBy { it.name }

    private val byName: Map<String, TypeInfo> = this.types.associateBy { it.name }

    /** The type named [name], or null if the writer has no such type. */
    public fun type(name: String): TypeInfo? = byName[name]

    /**
     * The object [key] of [typeName] with all its members, to be the value of a `has` statement. The key gets the first
     * free number from 2 on where [usedKeys] holds it, and so does the key of every object inside it. A type the writer
     * does not know gives an object with an empty body.
     */
    public fun contained(key: String, typeName: String, usedKeys: Set<String>): KmodelObjectText =
        KmodelObjectTemplates(byName, usedKeys).plainObject(key, typeName)

    /**
     * The statement `has("relation") -> make object "key":"Type" { ... }` that gives an object of [typeName] through the
     * relation [relation], the object written as [contained] writes it and keyed by the relation. The relation and the
     * key are escaped alike, so they read the same in the text whatever characters the relation holds. An empty relation
     * gives an object with an empty key, an object without an id.
     */
    public fun has(relation: String, typeName: String, usedKeys: Set<String>): KmodelObjectText =
        KmodelObjectTemplates(byName, usedKeys).plainHas(relation, typeName)

    /**
     * The root object of [typeName] with all its members. Its key is the type name with a small first letter, which the
     * completion gives a top-level object as well. The keys avoid [usedKeys] as those of [contained] do.
     */
    public fun root(typeName: String, usedKeys: Set<String>): KmodelObjectText =
        contained(KmodelObjectTemplates.rootKeyOf(typeName), typeName, usedKeys)

    /** The writer without types and an entry point that finds the types of a file. */
    public companion object {
        /** The writer without any type. It belongs to a file outside every modeling context. */
        public val NONE: KmodelObjectWriter = KmodelObjectWriter(emptyList())

        /**
         * The writer over the domain types of the modeling context of the file [path] in [project], as the completion
         * of a document of that file sees them: what dsl-tools read of the domain metamodel, completed by its syntax.
         * It is [NONE] for a file outside every context. It never throws, so a caller in the UI needs no guard: a
         * metamodel that cannot be converted gives [NONE] as well.
         */
        public fun of(project: ProjectAnalysis, path: Path): KmodelObjectWriter {
            val context = project.contextOf(path) ?: return NONE
            return runCatching { KmodelObjectWriter(ContextTypes.convert(context).domain) }.getOrDefault(NONE)
        }
    }
}
