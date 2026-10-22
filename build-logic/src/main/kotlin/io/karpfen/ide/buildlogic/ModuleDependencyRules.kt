package io.karpfen.ide.buildlogic

/**
 * The module dependency matrix: the allowed dependency directions between the karpfen IDE modules.
 *
 * Module boundaries are enforced by the build, because a layering that only lives in documents erodes. The core
 * modules are the UI-agnostic language core and see only each other; notation, runtime and bridges build on core; the
 * bridges are headless-first and use of the runtime only its protocol module; the UI sees every layer but the app and
 * the tools; the command-line tools see every layer but the UI and the app; the app wires everything. Every module's
 * `check` verifies it against [rules], and the architecture tests add the import rules that a dependency matrix cannot
 * express. A new module needs a rule here and an entry in the architecture tests' module table.
 *
 * Module identifiers are Gradle project paths. A pattern ending in `:*` matches every module below that
 * group (`:core:*` matches `:core:core-text`), the pattern `*` matches every module, any other pattern
 * matches exactly one project path.
 *
 * Pure logic without Gradle types, so it can be unit-tested directly; the Gradle wiring lives in
 * [VerifyModuleDependencies] and the `karpfen.kotlin-jvm` convention plugin.
 */
object ModuleDependencyRules {

    /** One row of the dependency matrix: modules matching [from] may depend on modules matching any of [allowed]. */
    data class Rule(val from: String, val allowed: List<String>)

    /** A project dependency declared in [configuration] of a module, pointing at [targetPath]. */
    data class DeclaredDependency(val configuration: String, val targetPath: String) {
        /** Serialized form used as a Gradle task input. */
        fun encode(): String = "$configuration$SEPARATOR$targetPath"

        companion object {
            private const val SEPARATOR = " -> "

            /**
             * Parses the serialized form written by [encode].
             *
             * @throws IllegalArgumentException if [encoded] does not contain the separator after a configuration name.
             */
            fun decode(encoded: String): DeclaredDependency {
                val index = encoded.indexOf(SEPARATOR)
                require(index > 0) { "Malformed declared dependency: '$encoded'" }
                return DeclaredDependency(encoded.substring(0, index), encoded.substring(index + SEPARATOR.length))
            }
        }
    }

    /**
     * The matrix. The first rule whose [Rule.from] matches the depending module applies. The architecture tests have no
     * allowed target: they read the source files of all modules from disk and must not depend on any module.
     */
    val rules: List<Rule> = listOf(
        Rule(":core:*", listOf(":core:*")),
        Rule(":notation:*", listOf(":core:*", ":notation:*")),
        Rule(":runtime:*", listOf(":core:*", ":runtime:*")),
        Rule(":bridges:*", listOf(":core:*", ":bridges:*", ":runtime:runtime-protocol")),
        Rule(":ui:*", listOf(":core:*", ":notation:*", ":runtime:*", ":bridges:*", ":ui:*")),
        Rule(":app:*", listOf("*")),
        Rule(":tools:cli", listOf(":core:*", ":notation:*", ":runtime:*", ":bridges:*")),
        Rule(":architecture-tests", emptyList()),
    )

    /** True if [projectPath] matches [pattern] (see the class documentation for the pattern syntax). */
    fun matches(pattern: String, projectPath: String): Boolean = when {
        pattern == "*" -> true
        pattern.endsWith(":*") -> projectPath.startsWith(pattern.removeSuffix("*")) &&
            projectPath.length > pattern.length - 1
        else -> pattern == projectPath
    }

    /** The rule that governs [from], or null if the module is not covered by the matrix. */
    fun ruleFor(from: String): Rule? = rules.firstOrNull { matches(it.from, from) }

    /** True if module [from] may depend on module [to]. A module may always depend on itself (e.g. test fixtures). */
    fun isAllowed(from: String, to: String): Boolean {
        if (from == to) return true
        val rule = ruleFor(from) ?: return false
        return rule.allowed.any { matches(it, to) }
    }

    /**
     * Human-readable violations of module [from]; empty if all project dependencies are allowed.
     *
     * [dependencies] are the project dependencies declared on the module's configurations. [resolved] are the
     * first-level project dependencies of its resolved classpaths ([DeclaredDependency.configuration] is then the
     * classpath, e.g. `compileClasspath`); they also contain dependencies added after the declarations were scanned or
     * during resolution (`withDependencies`, `defaultDependencies`). A forbidden target that is already reported as a
     * declaration is not repeated for the classpaths it reaches.
     */
    fun violations(
        from: String,
        dependencies: Collection<DeclaredDependency>,
        resolved: Collection<DeclaredDependency> = emptyList(),
    ): List<String> {
        val rule = ruleFor(from)
            ?: return listOf(
                "$from is not covered by the module dependency matrix; add a rule to " +
                    "build-logic/src/main/kotlin/io/karpfen/ide/buildlogic/ModuleDependencyRules.kt",
            )
        val allowedText = if (rule.allowed.isEmpty()) "no project dependencies" else rule.allowed.joinToString(", ")
        val declaredViolations = dependencies.filterNot { isAllowed(from, it.targetPath) }
        val reportedTargets = declaredViolations.map { it.targetPath }.toSet()
        val resolvedViolations = resolved
            .filterNot { isAllowed(from, it.targetPath) || it.targetPath in reportedTargets }
            .groupBy({ it.targetPath }, { it.configuration })
        return declaredViolations.map {
            "${it.configuration}(project(\"${it.targetPath}\")) is not allowed: ${rule.from} may depend on $allowedText"
        } + resolvedViolations.map { (target, classpaths) ->
            "project(\"$target\") on ${classpaths.distinct().joinToString(", ")} is not allowed: " +
                "${rule.from} may depend on $allowedText"
        }
    }
}
