package io.karpfen.ide.core.lang.fixtures

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.fixtures.ExampleContext
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.services.ProviderFailure
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.AnalyzedFile
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import java.util.Collections

/**
 * The example files (`Examples.contexts()`) as a workspace below [ROOT], analyzed by the conventions documented on
 * `ProjectAnalysis`, without the workspace's analyzer: normalized absolute paths, `TextSnapshot(text, 0)` per file
 * (a lineage of its own), syntax-only parses from `defaultLanguageRegistry()`, reads through core-document's
 * `DslReader` — the events file with the domain types as base types, the events and the models only against a domain
 * metamodel without errors.
 */
object ExampleProject {

    /** The (virtual) workspace root of the examples. */
    val ROOT: Path = Path.of("/workspace/examples").toAbsolutePath().normalize()

    /** The tokenizers and parsers of the built-in languages, environment descriptors included. */
    val registry: LanguageRegistry = defaultLanguageRegistry()

    /** The dsl-tools adapter of the reads. */
    private val reader = DslReader()

    /** The analysis of every example context (built once). */
    val analysis: ProjectAnalysis by lazy { analyze(Examples.contexts()) }

    /** The absolute path of the example at [examplePath] (below the fixture root, e.g. `karpfen-runtime/…/EVENTS.kmeta`). */
    fun pathOf(examplePath: String): Path = ROOT.resolve(examplePath).normalize()

    /** The example text at [examplePath]. */
    fun textOf(examplePath: String): String = Examples.file(examplePath).text

    /** Analyzes [contexts] like the workspace's analyzer would (see the class documentation). */
    fun analyze(contexts: List<ExampleContext>, generation: Long = 1): ProjectAnalysis {
        val diagnostics = LinkedHashMap<Path, List<Diagnostic>>()
        val analyses = contexts.map { example ->
            val files = example.files.associate { file -> pathOf(file.path) to analyzedFile(pathOf(file.path), file.text) }
            val metamodelFile = example.metamodel?.let { files.getValue(pathOf(it.path)) }
            val metamodelRead = metamodelFile
                ?.let { reader.kmeta(it.snapshot) }
                ?.also { record(diagnostics, metamodelFile, it.diagnostics) }
            val domain = metamodelRead?.takeUnless { it.hasErrors }?.model
            val eventsFile = example.events?.let { files.getValue(pathOf(it.path)) }
            val events = if (domain == null || eventsFile == null) {
                null
            } else {
                reader.kmeta(eventsFile.snapshot, domain.types).also { record(diagnostics, eventsFile, it.diagnostics) }.model
            }
            val models = example.models.mapNotNull { model ->
                val file = files.getValue(pathOf(model.path))
                domain
                    ?.let { reader.kmodel(file.snapshot, it) }
                    ?.also { record(diagnostics, file, it.diagnostics) }
                    ?.model
                    ?.let { file.path to it }
            }.toMap()
            val machines = example.stateMachines.mapNotNull { machine ->
                val file = files.getValue(pathOf(machine.path))
                reader.kstates(file.snapshot)
                    .also { record(diagnostics, file, it.diagnostics) }
                    .model
                    ?.let { file.path to it }
            }.toMap()
            ContextAnalysis(
                context = ModelingContext(
                    directory = pathOf(example.directory),
                    descriptor = null,
                    metamodel = example.metamodel?.let { pathOf(it.path) },
                    events = example.events?.let { pathOf(it.path) },
                    models = example.models.map { pathOf(it.path) },
                    stateMachines = example.stateMachines.map { StateMachineBinding(pathOf(it.path), attachedTo = null) },
                ),
                files = files,
                metamodel = metamodelRead?.model,
                events = events,
                models = models,
                stateMachines = machines,
            )
        }
        return ProjectAnalysis(generation, analyses, diagnostics)
    }

    /** The analyzed file at [path] with [text] (version 0, a lineage of its own, syntax-only parse). */
    fun analyzedFile(path: Path, text: String): AnalyzedFile {
        val support = registry.support(languageOf(path))
        val snapshot = TextSnapshot(text, 0)
        val tokens = support.tokenizer.tokenize(snapshot)
        val parse = support.parser.parse(snapshot, SemanticMode.SYNTAX_ONLY)
        return AnalyzedFile(path, FileKind.of(path.fileName.toString()), snapshot, parse, tokens.diagnostics)
    }

    /**
     * A document at [path] with [text] as the editor would hand it to the services: tokens and a syntax-only parse of
     * [version]; the last good parse is [lastGoodParse], else the parse itself when it is syntax-clean.
     */
    fun document(path: Path, text: String, version: Long = 1, lastGoodParse: ParseOutcome? = null): LanguageDocument {
        val language = languageOf(path)
        val support = registry.support(language)
        val snapshot = TextSnapshot(text, version)
        val parse = support.parser.parse(snapshot, SemanticMode.SYNTAX_ONLY)
        val tokens = support.tokenizer.tokenize(snapshot)
        return LanguageDocument(path, language, snapshot, tokens, parse, lastGoodParse ?: parse.takeIf { it.isSyntaxClean })
    }

    /** The example at [examplePath] as a document, its text changed by [edit]. */
    fun example(examplePath: String, edit: (String) -> String = { it }): LanguageDocument =
        document(pathOf(examplePath), edit(textOf(examplePath)))

    /** A request for [document] against [analysis]. */
    fun request(document: LanguageDocument, analysis: ProjectAnalysis = this.analysis): FeatureRequest =
        FeatureRequest(document, analysis, analysis.contextOf(document.path))

    /** The default services over [analysis]; [failures] collects every provider failure the facade reports. */
    fun services(
        analysis: ProjectAnalysis = this.analysis,
        failures: MutableList<ProviderFailure> = Collections.synchronizedList(ArrayList()),
    ): LanguageServices = defaultLanguageServices(MutableStateFlow(analysis), onProviderFailure = { failures += it })

    /** The language of the file at [path], which must be a karpfen source. */
    private fun languageOf(path: Path): KarpfenLanguage =
        requireNotNull(KarpfenLanguage.fromFileName(path.fileName.toString())) { "not a karpfen file: $path" }

    /** Adds the conversion [diagnostics] of [file] to the project diagnostics [into]. */
    private fun record(into: MutableMap<Path, List<Diagnostic>>, file: AnalyzedFile, diagnostics: List<Diagnostic>) {
        if (diagnostics.isNotEmpty()) into[file.path] = into[file.path].orEmpty() + diagnostics
    }
}

/** Example paths used by many tests. */
object ExamplePaths {
    const val ROBOT_DIR = "karpfen-runtime/single_robot_example"
    const val ROBOT_KMETA = "$ROBOT_DIR/cleaning_robot.kmeta"
    const val ROBOT_EVENTS = "$ROBOT_DIR/EVENTS.kmeta"
    const val ROBOT_KMODEL = "$ROBOT_DIR/cleaning_robot.kmodel"
    const val ROBOT_KSTATES = "$ROBOT_DIR/cleaning_robot.kstates"
    const val DRONE_DIR = "karpfen-runtime/delivery_drone_example"
    const val DRONE_KSTATES = "$DRONE_DIR/delivery_drone.kstates"
    const val DRONE_EVENTS = "$DRONE_DIR/EVENTS.kmeta"
    const val SWARM_KSTATES = "karpfen-runtime/drone_swarm_example/drone_swarm.kstates"
    const val SWARM_KMODEL = "karpfen-runtime/drone_swarm_example/drone_swarm.kmodel"
    const val CAR_KSTATES = "karpfen-runtime/autonomous_car_example/autonomous_car.kstates"
}

/** The text with the caret marker `‸` removed and the marker's offset. */
fun caret(textWithMarker: String): Pair<String, Int> {
    val offset = textWithMarker.indexOf(CARET)
    require(offset >= 0) { "no caret marker in the text" }
    return textWithMarker.removeRange(offset, offset + 1) to offset
}

/** The offset right after the first occurrence of [needle] in [text] (plus [shift]). */
fun after(text: String, needle: String, shift: Int = 0): Int {
    val index = text.indexOf(needle)
    require(index >= 0) { "'$needle' not found" }
    return index + needle.length + shift
}

/** The offset of the first occurrence of [needle] in [text] (plus [shift]). */
fun at(text: String, needle: String, shift: Int = 0): Int {
    val index = text.indexOf(needle)
    require(index >= 0) { "'$needle' not found" }
    return index + shift
}

/** The caret marker of [caret]. */
const val CARET: Char = '‸'
