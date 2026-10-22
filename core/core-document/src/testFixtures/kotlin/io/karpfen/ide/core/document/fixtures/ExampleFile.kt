package io.karpfen.ide.core.document.fixtures

import io.karpfen.ide.core.document.KarpfenLanguage

/**
 * The repository an example file was copied from (see `examples/NOTICE.md`).
 *
 * @property directoryName the first path segment of the examples copied from it.
 * @property commit the commit they were copied at.
 */
public enum class ExampleOrigin(public val directoryName: String, public val commit: String) {
    /** karpfenproject/karpfen-runtime, `examples/`. */
    KARPFEN_RUNTIME("karpfen-runtime", "d4c2059"),

    /** karpfenproject/karpfen-dsl-tools, `example/`. */
    KARPFEN_DSL_TOOLS("karpfen-dsl-tools", "859a95d"),
}

/**
 * One example file from the test fixtures.
 *
 * @property path path below the fixture root, `/`-separated, e.g. `karpfen-runtime/single_robot_example/EVENTS.kmeta`.
 * @property language the file's language (by extension).
 * @property text the file content, decoded as UTF-8 (unmodified; the examples use `\n` separators).
 */
public data class ExampleFile(public val path: String, public val language: KarpfenLanguage, public val text: String) {

    /** The origin repository (the first path segment). */
    public val origin: ExampleOrigin
        get() = ExampleOrigin.entries.first { path.startsWith(it.directoryName + "/") }

    /** The file name, e.g. `EVENTS.kmeta`. */
    public val fileName: String get() = path.substringAfterLast('/')

    /** The directory below the fixture root, e.g. `karpfen-runtime/single_robot_example`. */
    public val directory: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")

    /** True for an event metamodel (`EVENTS.kmeta`, case-insensitive). */
    public val isEventsMetamodel: Boolean get() = fileName.equals("EVENTS.kmeta", ignoreCase = true)

    /** Number of lines (`\n`-separated; a trailing newline adds an empty last line). */
    public val lineCount: Int get() = text.count { it == '\n' } + 1

    override fun toString(): String = path
}

/**
 * The example files of one directory, which form one implicit modeling context (a directory not covered by an
 * environment descriptor): the domain metamodel, the optional event metamodel, models and state machines.
 *
 * @property directory the directory below the fixture root.
 * @property files the example files of [directory], sorted by path.
 */
public data class ExampleContext(public val directory: String, public val files: List<ExampleFile>) {

    /** The domain metamodel: the alphabetically first `.kmeta` that is not `EVENTS.kmeta`, or null. */
    public val metamodel: ExampleFile?
        get() = files.filter { it.language == KarpfenLanguage.KMETA && !it.isEventsMetamodel }.minByOrNull { it.fileName }

    /** The event metamodel `EVENTS.kmeta`, or null. */
    public val events: ExampleFile? get() = files.firstOrNull { it.isEventsMetamodel }

    /** The `.kmodel` files. */
    public val models: List<ExampleFile> get() = files.filter { it.language == KarpfenLanguage.KMODEL }

    /** The `.kstates` files. */
    public val stateMachines: List<ExampleFile> get() = files.filter { it.language == KarpfenLanguage.KSTATES }

    override fun toString(): String = directory
}
