package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import java.nio.file.Path

/**
 * `karpfen validate <dir>` (spec §9): loads a `Workspace` of [directory] without watching, runs the project analysis
 * (`Workspace.awaitAnalysis`) plus per-file tokenizing and syntax parsing of every karpfen file, and reports LEXER,
 * PARSER, DSL_TOOLS and IDE diagnostics. dsl-tools prints to stdout while converting, so the analysis runs inside
 * [StandardOutputGuard.silenced].
 */
public class ValidateCommand(
    private val languages: LanguageRegistry = defaultLanguageRegistry(),
    private val outputGuard: StandardOutputGuard = StandardOutputGuard(),
) {
    /** Validates [directory]. */
    public suspend fun run(directory: Path): ValidationReport = TODO("WP-APP: validate a directory")
}
