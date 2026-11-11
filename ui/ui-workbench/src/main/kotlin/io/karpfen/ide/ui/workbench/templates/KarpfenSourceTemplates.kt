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
package io.karpfen.ide.ui.workbench.templates

/**
 * The contents of new KMeta, KModel and KStates files, as [FileTemplates] documents them. Each tokenizes and parses
 * without diagnostics. It is pure.
 *
 * Names come from the file's base name. The example type of a metamodel is the base name in PascalCase, as [typeName]
 * builds it. A model or state machine falls back to the same type when the context knows no domain type. The files
 * `robot.kmeta`, `robot.kmodel` and `robot.kstates` created in an empty directory therefore fit together.
 */
internal object KarpfenSourceTemplates {

    /** One example type named after the file. */
    fun kmeta(baseName: String): String {
        val type = typeName(baseName)
        return """
            |type "$type" "A $type of the domain" {
            |    prop("name", "string")
            |}
            |""".trimMargin()
    }

    /**
     * One root object keyed by the file name. Its type is the context's first domain type, or the type named after the
     * file if the context knows none.
     */
    fun kmodel(baseName: String, context: TemplateContext): String {
        val type = context.typeNames.firstOrNull() ?: typeName(baseName)
        return """
            |make object "${quoted(baseName)}":"${quoted(type)}" {
            |}
            |""".trimMargin()
    }

    /**
     * A state machine with one initial state. It is attached to the context's first domain type, or to the type named
     * after the file if the context knows none.
     */
    fun kstates(baseName: String, context: TemplateContext): String {
        val type = context.typeNames.firstOrNull() ?: typeName(baseName)
        return """
            |STATEMACHINE ATTACHED TO "${quoted(type)}" {
            |
            |    STATES {
            |        INITIAL STATE "idle" { }
            |    }
            |
            |    TRANSITIONS {
            |    }
            |}
            |""".trimMargin()
    }

    /**
     * [baseName] in PascalCase, built from its letters and digits. For example, `cleaning_robot` becomes
     * `CleaningRobot`. A name that would start with a digit gets a leading `T`. A name without letters or digits
     * becomes `Example`.
     */
    fun typeName(baseName: String): String {
        val name = baseName.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        return when {
            name.isEmpty() -> "Example"
            name.first().isDigit() -> "T$name"
            else -> name
        }
    }

    /**
     * [value] without the characters that a karpfen string literal cannot hold. These are quotes, backslashes and line
     * breaks.
     */
    private fun quoted(value: String): String = value.filterNot { it == '"' || it == '\\' || it == '\n' || it == '\r' }
}
