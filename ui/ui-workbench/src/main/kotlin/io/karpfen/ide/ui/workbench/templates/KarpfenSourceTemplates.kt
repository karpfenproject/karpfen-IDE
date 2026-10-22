package io.karpfen.ide.ui.workbench.templates

/**
 * Contents of new KMeta, KModel and KStates files (documented on [FileTemplates]); each tokenizes and parses without
 * diagnostics. Names come from the file's base name: the example type of a metamodel is the base name in PascalCase
 * ([typeName]), which is also the type a model or state machine falls back to when the context knows no domain type —
 * so `robot.kmeta`, `robot.kmodel` and `robot.kstates` created in an empty directory fit together. Pure.
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

    /** One root object of the context's first domain type (else the type named after the file), keyed by the file name. */
    fun kmodel(baseName: String, context: TemplateContext): String {
        val type = context.typeNames.firstOrNull() ?: typeName(baseName)
        return """
            |make object "${quoted(baseName)}":"${quoted(type)}" {
            |}
            |""".trimMargin()
    }

    /** A state machine attached to the context's first domain type (else the type named after the file), one initial state. */
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

    /** [baseName] in PascalCase from its letters and digits (`cleaning_robot` → `CleaningRobot`), or `Example`. */
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

    /** [value] without the characters a karpfen string literal cannot hold (quotes, backslashes, line breaks). */
    private fun quoted(value: String): String = value.filterNot { it == '"' || it == '\\' || it == '\n' || it == '\r' }
}
