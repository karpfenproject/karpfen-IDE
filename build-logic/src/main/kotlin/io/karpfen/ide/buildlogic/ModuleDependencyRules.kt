package io.karpfen.ide.buildlogic

/**
 * The allowed dependency directions between karpfen IDE modules (D7, docs/architecture-proposal.md §4).
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

            fun decode(encoded: String): DeclaredDependency {
                val index = encoded.indexOf(SEPARATOR)
                require(index > 0) { "Malformed declared dependency: '$encoded'" }
                return DeclaredDependency(encoded.substring(0, index), encoded.substring(index + SEPARATOR.length))
            }
        }
    }

    /** The matrix. The first rule whose [Rule.from] matches the depending module applies. */
    val rules: List<Rule> = listOf(
        Rule(":core:*", listOf(":core:*")),
        Rule(":notation:*", listOf(":core:*", ":notation:*")),
        Rule(":runtime:*", listOf(":core:*", ":runtime:*")),
        Rule(":bridges:*", listOf(":core:*", ":bridges:*", ":runtime:runtime-protocol")),
        Rule(":ui:*", listOf(":core:*", ":notation:*", ":runtime:*", ":bridges:*", ":ui:*")),
        Rule(":app:*", listOf("*")),
        Rule(":tools:cli", listOf(":core:*", ":notation:*", ":runtime:*", ":bridges:*")),
        // The architecture tests scan source files; they must not depend on any module.
        Rule(":architecture-tests", emptyList()),
    )

    private val dependencyScopeNames = listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly")

    /**
     * True for configurations in which dependencies are declared: `api`, `implementation`, `compileOnly`,
     * `compileOnlyApi`, `runtimeOnly` and their source-set variants (`testImplementation`, `testRuntimeOnly`, ...).
     */
    fun isDependencyScope(configurationName: String): Boolean = dependencyScopeNames.any { scope ->
        configurationName == scope || configurationName.endsWith(scope.replaceFirstChar(Char::uppercaseChar))
    }

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

    /** Human-readable violations for the [dependencies] declared by module [from]; empty if all are allowed. */
    fun violations(from: String, dependencies: Collection<DeclaredDependency>): List<String> {
        val rule = ruleFor(from)
            ?: return listOf(
                "$from is not covered by the module dependency matrix; add a rule to " +
                    "build-logic/src/main/kotlin/io/karpfen/ide/buildlogic/ModuleDependencyRules.kt",
            )
        val allowedText = if (rule.allowed.isEmpty()) "no project dependencies" else rule.allowed.joinToString(", ")
        return dependencies
            .filterNot { isAllowed(from, it.targetPath) }
            .map { "${it.configuration}(project(\"${it.targetPath}\")) is not allowed: ${rule.from} may depend on $allowedText" }
    }
}
