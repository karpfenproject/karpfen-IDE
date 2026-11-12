# Developer notes

These notes are for the people who maintain the karpfen IDE. They explain how to build and test it, the conventions
the code follows, the design decisions and why they were made, the behaviour that surprises people, and what is still
open. The [README](../README.md) gives the short overview. The [getting started guide](getting-started.md) walks
through a first model. The [architecture document](archdoc.md) explains the modules. Everything else lives in the KDoc
of the types named here. Search for a type name to find its file.

## Building and testing

### Requirements

Gradle runs on JDK 17 or newer. The build compiles with a JDK 21 toolchain and downloads it through the foojay resolver
when it is missing. The Gradle wrapper pins Gradle 9.8.2. The library versions are in `gradle/libs.versions.toml`. The
version of the foojay resolver is written twice, in the `plugins` block of `settings.gradle.kts` and in the catalog,
because the catalog is not available there. Change both together. `gradle.properties` turns on the configuration
cache, the build cache and parallel execution for every build, in both ways of resolving dsl-tools.

The build needs network access to the Gradle Plugin Portal, Maven Central and Google Maven. Downloading a JDK also needs
the foojay discovery API at `api.foojay.io` and the download host of the JDK vendor.

The submodules must be checked out, including the nested ones:

```bash
git submodule update --init --recursive
```

The nested karpfen-dsl-tools inside karpfen-runtime is needed by a plain `./gradlew build`. The task
`bundleRuntimeSources` packs the runtime sources into the IDE and fails when a nested submodule is missing.

Docker is only needed to run models in the IDE's own container and for the live tests. Nothing in `./gradlew build`
starts a container.

### Gradle tasks

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles everything, runs the unit and architecture tests and checks the module dependencies. |
| `./gradlew :core:core-lang:test` | Runs the tests of one module. |
| `./gradlew :app:ide:run` | Starts the IDE. Add `--args=<folder>` to open a folder. Without one it shows the start page with Open Folder… and the recent folders. |
| `./gradlew :app:ide:runWithFailingReaders --args=<folder>` | Starts the IDE with dsl-tools readers that fail on deep nesting. |
| `./gradlew :tools:cli:installDist` | Builds the command-line tool into `tools/cli/build/install/karpfen/bin/karpfen`. |
| `./gradlew :tools:cli:run` | Prints the usage of the command-line tool. |
| `./gradlew :app:ide:createDistributable` | Builds the application image without an installer. |
| `./gradlew :app:ide:packageDistributionForCurrentOS` | Builds the installer of the current platform. |
| `./gradlew :runtime:runtime-host:liveDockerTest` | Runs the host tests against the local Docker engine. |
| `./gradlew :bridges:bridge-runner:installDist` | Builds the bridge runner, which is still a stub. |

Test reports are in `<module>/build/reports/tests/test/index.html`.

The installer is a dmg on macOS, an msi on Windows and a deb on Linux. The dmg lands in
`app/ide/build/compose/binaries/main/dmg/`. The package version is 1.0.0 on macOS and 0.1.0 elsewhere, because jpackage
rejects a major version 0 on macOS. The bundled JDK gets the extra modules `java.instrument` and `jdk.unsupported`.
When a new library needs more modules, `:app:ide:suggestRuntimeModules` lists them. The packaging `javaHome` is pinned
to the toolchain, so the installer bundles JDK 21 whatever JDK runs Gradle.

`runWithFailingReaders` starts `FailingReadersLaunch` from the test sources with the production `logback.xml`. Its
readers are `SmallStackDslReader`, which runs each dsl-tools read on a thread with a small stack. No text a user writes
makes the real readers fail, so this task is how a check by hand gets reader failures into the Language Tools Output.
The task runs on the test classpath, which also holds the command-line tool's `logback.xml`. So it names the
production file of app/ide by its file URL, because a resource name that two files answer makes logback print its
status on the console.

### Test tags

The convention plugin `karpfen.kotlin-jvm` excludes three JUnit tags from the normal test task. `TestTags` in
build-logic names them.

- `budget` marks time-budget tests. They are excluded from every test task, those of build-logic included, and there
  is no switch to run them. `BudgetTestTaggingTest` makes sure that every timing test carries the tag and that every
  test task excludes it. A functional check never lives in a budget test, so it keeps running.
- `live-runtime` marks tests against a real karpfen runtime. They run only in the task `liveRuntimeTest` of
  runtime-protocol and runtime-session. `LiveRuntimeTaggingTest` checks this wiring.
- `live-docker` marks tests against the local Docker engine. They run only in `liveDockerTest` of runtime-host. The
  engine test builds a tiny image `FROM scratch` with the label `io.karpfen.ide.test=true` and removes it again. The
  host test builds the IDE's runtime image unless it exists, starts a container for a workspace in a temporary folder,
  adopts it from a second host, stops it and removes every container it made. The runtime image stays.

Both live tasks are never up to date, fail when they find no test, and stop after a hard timeout of 30 minutes.

### Live runtime tests

`runtime/live-runtime.sh` runs the runtime in throwaway Docker containers and points the live tests at it. Every
container, volume and image it makes carries the label `io.karpfen.ide.test=true`. It never touches anything else. It
refuses a Docker engine that is not local, because the runtime has no authentication and would then be published on
another machine.

| Command | What it does |
|---|---|
| `runtime/live-runtime.sh build` | Checks the local Docker engine and the runtime checkout, then builds the test image. |
| `runtime/live-runtime.sh test [gradle args]` | Removes orphans, builds, starts a runtime, runs the live tests, tears it down. Gradle arguments replace the two live tasks, as in `test :runtime:runtime-session:liveRuntimeTest --tests '*Observation*'`. |
| `runtime/live-runtime.sh up` | Starts one runtime and prints its URL, its run id and how to remove it. |
| `runtime/live-runtime.sh down [run id]` | Tears down one run of this checkout, or all of them. |
| `runtime/live-runtime.sh status` | Lists the containers, volumes and images of the script. |
| `runtime/live-runtime.sh cleanup` | Removes everything the script made and reports what stays. |

The script needs git, curl and the nested submodule of the runtime (`git -C karpfen-runtime submodule update --init`).
It reads these environment variables:

- `KARPFEN_RUNTIME_DIR`, the runtime checkout to build. The default is the submodule.
- `KARPFEN_LIVE_HEALTH_TIMEOUT_S`, the whole health wait in seconds, 180 by default.
- `KARPFEN_LIVE_TICK_DELAY_MS`, 50 by default.
- `KARPFEN_LIVE_EVENT_TTL_MS`, 3000 by default.
- `KARPFEN_LIVE_TRACING`, true by default.

Run folders and build logs go to `build/live-runtime/`. The log and the engine traces of each run stay in
`build/live-runtime/runs/<run id>`, also when the tests fail or are interrupted. The first build downloads the base
images and the runtime's dependencies and takes minutes. Later builds reuse Docker's layer cache. `cleanup` leaves the
base images and Docker's build cache alone, because other projects may share them, and says so. The test models need
no Python except the depot, which creates objects at run time. Python only ever runs inside the container.

To run the live tests against a runtime you started yourself, pass `-Pkarpfen.runtime.url=<url>` to
`liveRuntimeTest`. The optional property `karpfen.runtime.logs` names the folder of the runtime's logs, so that
failure messages can point to it. `LiveRuntimeLock` keeps two live test tasks from using the same runtime at the same
time. When both live tasks run in one build, the one of runtime-session runs after the one of runtime-protocol, so a
change of the wire format shows first in the protocol's tests.

A test class against a real runtime sits in a runtime module, is named `Live*Test` and carries `@LiveRuntime`. Every
test class named that way must carry the annotation, so a forgotten annotation cannot make a live test run in every
build. `LiveRuntimeTaggingTest` checks all of this.

`RuntimeContainerScriptTest` runs the script in every build. It replaces docker, git and curl with shell functions and
runs on a virtual clock, so it needs no Docker.

### Where dsl-tools comes from

`settings.gradle.kts` decides how karpfen-dsl-tools is resolved. By default it is a composite build of the submodule
`karpfen-dsl-tools`. The property `dslToolsPath` points at another checkout. It is read from `-PdslToolsPath`, then from
`local.properties`. A blank value switches to the published artifact from GitHub Packages, with the credentials
`GitHubPackagesUsername` and `GitHubPackagesPassword`. Only the `io.karpfen` group is fetched from there, and that
group is fetched from nowhere else. `local.properties` treats a backslash as an escape character, so a Windows path is
written with forward slashes, as in `C:/work/karpfen-dsl-tools`.

The build needs dsl-tools 2.0 and fails with a clear message on an older checkout or an uninitialized submodule. It
never reads or writes `~/.m2`. The active mode is logged during configuration, but not when the configuration cache is
reused. Run with `--no-configuration-cache` or use `dependencyInsight` to see it.

### Command-line tool

```bash
karpfen validate <folder> [--format text|json] [--verbose]
```

It runs the same project analysis as the IDE and reports the same problems as the Problems view, because both read
`Workspace.problems`. The exit codes are in `ExitCodes`: 0 for no errors, 1 for errors found, 2 for a usage error and 3
for a failure of the tool. The report goes to stdout and messages go to stderr, both in UTF-8. `--verbose` also prints
the dsl-tools log on stderr. Positions are one-based and paths are relative to the folder. A file that cannot be read
gets the error `unreadable` at line 1, column 1, so a folder the tool could not read never passes. A text report line
looks like this:

```
karpfen-runtime/single_robot_example/cleaning_robot.kstates:8:13: error: extraneous input 'ENTRY' expecting '{' [syntax-error]
```

There is no deploy command yet.

### Settings, logs and launch options

The IDE keeps its files in `~/.karpfen-ide`. The environment variable `KARPFEN_IDE_HOME` moves that folder. The launcher
turns it into the system property `karpfen.ide.home`.

`settings.json` holds only what belongs to the user and the machine: the recent folders (at most 10) and the window
bounds. Unknown keys are ignored. A corrupt file or a file that is not UTF-8 is backed up and replaced by the defaults.

Everything else belongs to the open project and is stored in `.karpfenproject` in the project folder: the theme, the
scale of the workbench, the editor font size (13 by default), the tool window layout, the runtime host and the open
files with the active one. The file is plain text with one `key = value` per line, as `ProjectSettingsFile` reads and
writes it. The host is either `docker` or `url`, and the URL defaults to `http://127.0.0.1:8080`. The theme can be
`light`, `dark` or `system`, and `system` is the default, but the menu offers only Light and Dark. Open files are named
relative to the folder with forward slashes, so the file means the same on every machine and can be committed. Reading
never fails: a value that makes no sense falls back to its default, and lines with unknown keys are kept and written
back.

The IDE refreshes the project tree itself right after it wrote the project file for the first time, because the file
watcher needs seconds to notice a new file on some platforms.

In memory both parts are one `IdeSettings` value, and `SettingsRepository` keeps them apart on disk. `ProjectFlow`
switches the project part when a folder opens and restores the layout and the tabs from it. The project file is written
only when a project value changes, so a folder that is only looked at gets no file. While no folder is open, the project
values are the defaults and are stored nowhere.

There are two log files in `logs/`:

- `ide.log` gets INFO and above from the IDE. The console only gets WARN and above, on stderr.
- `dsl-tools.log` gets everything dsl-tools logs. The logger `io.karpfen.dsl` does not pass its lines to the root
  logger. `DslToolsThresholdFilter` forwards its WARN and ERROR lines to `ide.log`. The MDC key `karpfen.file` names the
  file dsl-tools was reading.

`app/ide/src/main/resources/logback.xml` describes this routing. The tests of app/ide log through a console-only
`logback-test.xml`, and `ProductionLogging` configures a logger context of its own from the production file.

`KARPFEN_IDE_EXIT_AFTER_MS` makes the IDE exit with status 0 after that many milliseconds. Together with a throwaway
home and a copy of the examples it gives a quick launch check. After this run the log contains no `ERROR` line:

```bash
rm -rf /tmp/karpfen-examples /tmp/karpfen-home
cp -R core/core-document/src/testFixtures/resources/examples /tmp/karpfen-examples
KARPFEN_IDE_HOME=/tmp/karpfen-home KARPFEN_IDE_EXIT_AFTER_MS=6000 ./gradlew :app:ide:run --args=/tmp/karpfen-examples
grep ERROR /tmp/karpfen-home/logs/ide.log
```

### Checking by hand

Some behaviour can only be checked in the running IDE: the native menu bar, typing on a real keyboard layout, focus
and mouse handling, the file watcher and quitting. Check it before a release, on a Mac with the German input source
selected. Copy the examples to a scratch folder and start the IDE on it with a throwaway home, so your own settings
and log stay untouched:

```bash
rm -rf /tmp/karpfen-smoke /tmp/karpfen-smoke-home
cp -R core/core-document/src/testFixtures/resources/examples /tmp/karpfen-smoke
KARPFEN_IDE_HOME=/tmp/karpfen-smoke-home ./gradlew :app:ide:run --args=/tmp/karpfen-smoke
```

Then check these things:

- On a German keyboard on macOS, Option+8, 9, 5, 6, 7, L and N type their characters. Dead keys, input methods and
  the press-and-hold accent menu work, and a waiting dead key draws its mark at the caret. Option+L must not open Go
  to Line.
- Typing `{`, `(`, `[` or `"` inserts the pair. Typing the closer steps over it, and one Backspace removes both.
- Cmd+Shift+7 toggles a line comment, with `#` inside an `EVAL` body.
- Ctrl+M opens completion and types nothing.
- One Cmd+Z undoes exactly one step, also when chosen from the menu.
- A change made outside the IDE shows up. On macOS this takes up to about 10 seconds, because the JDK's file watcher
  polls there. An unsaved edit then gets the banner with Reload and Keep mine.
- Cmd+Q with unsaved changes asks what to do. Window bounds and recent folders come back after a restart.
- Running a model works against Docker and against a URL.
- `grep ERROR` on `ide.log` prints nothing at the end.

To check the nesting limit and the Language Tools Output, generate deeply nested state machines into the copy:

```bash
mkdir /tmp/karpfen-smoke/deep && printf 'type "T" "t" {\n}\n' > /tmp/karpfen-smoke/deep/deep.kmeta
guards() { printf 'STATEMACHINE ATTACHED TO "T" {\nSTATES {\nINITIAL STATE "s0" {\nDO {\n'; for i in $(seq "$1"); do printf 'IF IN SCOPE("a") {\n'; done; printf 'SET("x", "1")\n'; for i in $(seq "$1"); do printf '}\n'; done; printf '}\n}\n}\nTRANSITIONS { }\n}\n'; }
guards 400 > /tmp/karpfen-smoke/deep/too_deep.kstates
for n in $(seq 40); do guards 280 > /tmp/karpfen-smoke/deep/guards_$n.kstates; done
```

In the normal IDE, Problems lists exactly one error, `nesting-too-deep` in `too_deep.kstates` at `301:18`, the brace
of the first guard too many. The 40 files of 280 guards are within the limit and clean, and the Language Tools Output
stays empty. Then quit and start `runWithFailingReaders` with the same home and folder. Each of the 40 files gets one
`reader.failure` error at its start, and the status bar shows `Language tools: 40`. A click opens the tool window in
follow mode. Scrolling up turns follow off, and scrolling back to the end turns it on again. Hiding and showing the
tool window keeps the reading position. Clear empties it, and `logs/dsl-tools.log` keeps the warnings with their
files.

Note every difference with what you did and what you saw, and keep `logs/ide.log` and `logs/dsl-tools.log` of the
throwaway home.

## Conventions

### Documentation

Declarations are documented in KDoc that explains what they are, what they promise and why they are built that way.
The KDoc starts with a summary sentence, links types and members as `[Name]`, and uses tags such as `@param` only where
they add something. In a Gradle script, KDoc may also document a top-level statement or block, such as
`dependencies { }`, because a script has no other place for it. KDoc must stand right before what it documents.

There are no line comments and no block comments other than KDoc. When code needs an explanation, a well-named
function or value carries it in its KDoc, or the KDoc of the enclosing declaration explains it. Never write the
character pairs that open or close a block comment inside comment text. Kotlin nests block comments, so such a pair
breaks the build. Every Kotlin file and Gradle script starts with the Apache 2.0 license header that the sources of
karpfen-dsl-tools carry too. `LicenseHeader` in the architecture tests holds the exact text. The shell script
`runtime/live-runtime.sh` carries the same text as `#` lines right after its first line.

Markdown is kept to five files: `README.md`, the three documents in `docs` and the license notice of the copied
example files in `core/core-document/src/testFixtures/resources/examples/NOTICE.md`. These documents stay short and
point to the code. KDoc never sends the reader to a markdown document. It explains the thing itself.

Write plain and simple English in short sentences. Name things in words, never by an id from another document, a
section number or a numbered phase or increment of a plan. Do not use the em dash, the en dash, the middle dot, the
bullet or the Unicode arrows. Write a verb instead of an arrow. ASCII arrows are fine inside backticks where they are
real syntax, as in `$(boundingBox->position)`.

`DocumentationStyleTest`, `KDocPlacementTest` and `LicenseHeaderTest` enforce these rules. `ReferenceIds` lists the id
patterns that are rejected and `TypographicCharacters` the characters. For Kotlin files and Gradle scripts these two
read only the comments, never strings or test names in backticks. They read the whole text of the markdown files,
the XML resources below `src/*/resources` and `gradle/libs.versions.toml`.

### Design

The code is object-oriented and uses the classic patterns where they fit. Each module, package and class has one job,
and modules depend on each other as little as possible. A class stays around 300 lines at most. A coordinator that
grows beyond that, such as `WorkbenchState` or `DefaultCodeEditorState`, delegates to collaborators instead. Seams are
interfaces with factory functions, collaborators are passed to constructors, and there are no singletons that hold
state. The application is wired by hand in its composition root, without a framework. Packages are organized by
feature and form no cycles. There are no Java sources, because the architecture tests read only Kotlin.

A factory function named like its interface creates the default implementation and lives in the interface's package,
such as `EditableText`, `DocumentSession` or `RuntimeClient`. A factory gets a descriptive name when it assembles a
family of products, such as `defaultLanguageRegistry` or `defaultEditorActions`, when it must live in another package
to keep the packages free of cycles, such as `defaultLanguageServices`, or when it provides a platform default, such
as `defaultWorkbenchServices`. In the UI modules, a mutable state holder without Compose is called `*Model` in the
editor's `model` packages, such as `CaretModel` and `FindModel`, and `*State` everywhere else. An immutable value that
a view renders is a `*UiState`, and an immutable result of one text version is a `*Snapshot` or a `Versioned*`. The
feature states of the workbench, such as `ProjectTreeState`, are plain public classes instead of interfaces. They do
no I/O, so the tests use the real ones.

A module declares a dependency as `api` only when the dependency's types appear in the module's own public API, and
as `implementation` otherwise. Code logs through SLF4J, with a logger in a private companion object. Only the
command-line tool and the bridge runner write to the standard streams.

Tokenizers, parsers, readers, the project analysis and the language services never let an exception reach the UI. A
failure becomes a diagnostic or an empty answer, and it is logged. A broken precondition is a programming error and
may throw.

Foreign libraries stay behind ports in a few adapter packages:

- ANTLR and dsl-tools in the `antlr` and `dsl` packages of core-document.
- Ktor in `transport.ktor` of runtime-protocol.
- docker-java in `docker.engine` of runtime-host.
- logback in the `logging` packages of the app and the command-line tool.
- Desktop file operations in `files.desktop`.
- Compose only in the ui modules and the app.

`ForeignApiConfinementTest`, `LayerImportRulesTest`, `UiLayeringTest` and `ComposeFreeStateTest` check this. The
dsl-tools allowance is a list: of dsl-tools only the semantic model (`meta`, `instance` and `states`) may be used
outside the `dsl` package, so a new dsl-tools package stays confined until someone adds it on purpose.

Values that cross threads are immutable. Mutable state stays on one thread, which is the UI thread for document texts
and editor state. State that changes over time is exposed as a `StateFlow` or a `Flow`. Composables contain no logic.
They render a state holder and call its methods.

Every fact has one source. The descriptor schema is derived from the typed `EnvironmentDescriptor`. The lexical facts
of the languages live in `KarpfenSyntax`, except the member keywords, which live in `MemberKind` of core-lang, and
`LexicalFactsTest` finds a second declaration by the shape of its code. `mergeDiagnostics` is the one rule for
merging diagnostics, `EditableText.mapRange` the one rule for moving ranges between versions,
`KarpfenLanguage.fromFileName` the one rule for file names, and the runtime's wire names live only in `WireNames`.

The core, notation and runtime modules use Kotlin's explicit API mode. `ModuleDependencyRules` in build-logic holds the
allowed dependencies between modules, and the task `verifyModuleDependencies` checks them in every module's `check`.
`PackageCycleTest` forbids cycles between packages.

### Tests

Tests use JUnit 6 with kotlin-test. Test functions are named with sentences in backticks. Coroutine code runs with
`runTest`, and Compose UI tests run headless with `runComposeUiTest`. Time is always virtual: debouncing runs on a
`StandardTestDispatcher`, undo coalescing on a `TestTimeSource`, and the runtime client and sessions with every
dispatcher and time source of their options on the test's scheduler. Most core, runtime and UI modules have a contract
test of their public values and wiring, such as `DocumentContractTest` or `WorkbenchContractTest`. runtime-host has
none of its own. `RuntimeHostApiTest` in architecture-tests checks its public API instead. ui-diagram has no contract
test. The
end-to-end tests run headless in the `e2e` test packages of app/ide, ui-workbench and ui-editor.

The shared fixtures live in test fixtures. Those of core-document hold `Examples`, unmodified copies of example files
from karpfen-runtime and karpfen-dsl-tools at the commits that `ExampleFile` names, listed in `examples/index.txt`,
and `DescriptorExamples`, descriptors written for the IDE's tests. They also hold `SyntheticKstates` for large
documents and `Mutations` for broken variants of the examples. core-workspace adds `TestWorkspaces` and
`FakeFileWatcher`. runtime-protocol adds the fake runtime, the goldens and `SynthesizedModels`, the small models of the
live tests. runtime-session adds `FakeRuntimeSession` and `ModelWireText`.

The fakes `FakeRuntime` and `FakeDockerEngine` only return the answers each test programs. They never simulate a state
machine or the runtime's behaviour. Scenarios that need real behaviour run only against a real runtime, in the live
tests. The scenario classes, such as `RuntimeContract` or `ObservationScenarios`, run through their `Live*Test`
subclasses, which carry `@LiveRuntime`. Failures that only a fake can produce are ordinary tests against the fake, such
as `RuntimeHazardsTest` and `RuntimeSessionFailureTest`. Live tests wait with `Patience`, which polls until a
condition holds. A test that proves something did not happen first waits for a later event that shows the system moved
on.

No test starts a Ktor server inside the JVM. The HTTP adapter is tested against Ktor's mock engine, and the mapping of
WebSocket frames is tested on its own. A real WebSocket session only runs in the live tests. Some tests need a feature
of the file system, such as POSIX permissions or case-sensitive names, and are skipped where it is missing.

A timeout in a test must be at least one second, so it guards against a hang and does not measure speed.
`BudgetTestTaggingTest` treats a literal timeout below one second as a time budget that needs the `budget` tag. A
timeout held in a named constant counts as generous. Its checkers read the test code lexically and have known blind
spots, which their KDoc lists. The runtime goldens (`RuntimeGoldens`) hold the exact texts the runtime writes on the
wire, and the client is tested against them. `RuntimeWireWriter` ports the runtime's JSON writers byte for byte, so
the client's reader can be checked against it. Anchor tests check the client's knowledge of the runtime against the
runtime's source.

The architecture tests need two things from Gradle. The build script passes them the submodule directories as a system
property, so they fail when run outside Gradle. They leave the submodules out, because those follow the rules of their
own repositories. The build scripts read `.gitmodules` with `GitSubmodules` from build-logic. Every file in the
repository root that they read, named in `RootFiles`, must also be listed in `rootFilesRead` of
`architecture-tests/build.gradle.kts`, so that a change to it runs the tests again. `PackageLayoutTest` checks this.
Build output is any `build` directory before the first `src` in a path, so a source package named `build` is fine, and
every `.gitignore` pattern for build directories is anchored at the root.

### Repositories and commits

The repositories the IDE needs are submodules: karpfen-dsl-tools and karpfen-runtime. Work on them happens only in
the submodules, never in a checkout next to this repository, because nothing here would record such work. Changes to
them are committed in the submodule on its branch and pushed before the IDE commit that moves the pointer. Commits
are authored by Karl Kegel, with short messages and no co-author trailers.

A small, clear bug in the runtime is fixed on its branch with IDE support, together with a regression test in the
runtime's own tests. A bigger finding, or one that touches the runtime's design, goes to the owner, and the IDE copes
with it in the meantime. The open points below list those findings. The runtime's own tests start its server inside
the test JVM on `127.0.0.1:8081`. Its build leaves out the tests that need Python on the host when it is given
`-PskipPythonTests`.

Work happens in small steps that are agreed before they start. There are no separate specification or planning
documents. A change is finished when it has been tried in the running IDE, not only when the tests pass.

Only Docker resources that carry the IDE's labels may be removed. Never prune.

## Key design decisions

### What the IDE is for

The IDE edits karpfen models as text, runs them in a karpfen runtime and shows them while they run. Diagrams that edit
the same models, and bridges to MQTT and ROS, are the next big parts. Work by several users on one model, running in a
browser and a generic graphical editor for any metamodel are not goals. The layering keeps them possible, but nothing
is built for them.

### The text files are the source of truth

Models live in plain `.kmeta`, `.kmodel`, `.kstates` and `.kenv.json` files. Every view, index and analysis is derived
from the text and thrown away when the text changes. A later diagram view must patch the text instead of keeping a
model of its own. This keeps the files usable in git and in any other editor. The semantic models that dsl-tools
builds are mutable classes, but the IDE never changes them. Every change is an edit of the text.

### dsl-tools is the language core

The IDE has no grammar of its own. It runs the ANTLR grammars that dsl-tools ships for highlighting and syntax
analysis on every edit, and the dsl-tools readers for the project analysis. dsl-tools 2.0 reports diagnostics with
source positions instead of throwing, so the IDE places its errors exactly. The IDE and the runtime are meant to share
one implementation of the languages instead of two that drift apart. Today the runtime still reads uploads with the
older dsl-tools 1.0, as the open points say.

Semantic checks of the languages belong in dsl-tools too. The project analysis adds no semantic rules of its own to
what the dsl-tools readers report. The workspace's own findings are about files, folders and descriptors, such as an
unreadable file or a context without a metamodel. The checks before a deployment are a different matter. They guard
against what the runtime would mishandle, such as a reference loop, not against errors in the languages.

### Compose Desktop and a custom editor

The UI is Compose Multiplatform for the desktop on the JVM. It was chosen over JavaFX because it fits Kotlin and
coroutines directly. JavaFX is not used at all, and the architecture tests reject it in every module. The text editor
is written from scratch on a Compose canvas. A custom editor gives full control over input, rendering and diagnostics,
and it can later run inside the cells of a diagram. Compose is confined to the ui modules and the app, so the rest of
the code does not depend on the toolkit.

### Diagrams are projections of the text

Three diagrams exist: the statechart of a state machine file, the class diagram of a metamodel file and the object
diagram of a model file. Each is placed automatically until the user arranges it by hand. Such a file can be shown as
its text, as its diagram, or as both next to each other, and the choice belongs to the file. The diagram is always the
one of the file itself. The rest of this section is the settled design that the later steps follow. They are, in this
order: a live view of the active states and of the current values while a model runs, and graphical edits that change
the text.

The diagram is drawn on a canvas of the IDE's own UI toolkit, not in an embedded web view. A web view would bring a
browser along, could not host the IDE's own editor in the text cells of the states, and would save little, because the
layout engine that web diagram tools use is the Eclipse Layout Kernel that already runs on the JVM.

The notation will be hybrid. The graph structure is drawn and edited graphically, and the leaves stay text in embedded
cells of the same code editor, with highlighting and checks. A state machine will show nested states, transitions with
a badge for their priority, because their order is part of the semantics, fork and join bars for `SPLIT` and `JOIN`,
the `ENTRY` and `DO` blocks as text cells and the macros in a side table. A metamodel is a class diagram. A model is an
object diagram, which will also be the view of a running model.

The class diagram and the object diagram share one model, `StructureDiagram`: boxes with a title and rows of text, and
links that are a containment or a reference. A type shows its simple properties as rows, and its `has` and `knows`
relations as links to the types they name. An object shows the values of its properties as rows, a `has` statement as
a link to the object it makes, and a `knows` statement as a link to the object with the key it names. A relation that
leads to nothing the file declares cannot be a link and is shown as a row. Both diagrams are derived from the outline
of the file, so they read the text exactly as the editor does.

What all diagrams share is written once. `DiagramCanvas` moves, zooms and fits the drawing and shows the grid, and
`DiagramEditing` turns drags into changes of the layout by hand. A secondary click only makes an offer, to add or
remove a waypoint, which the canvas shows as a context menu. The change is made when the user clicks the entry. Both work against two interfaces
of notation-diagram: `DiagramLayout`, which finds the box, the bar or the line under the pointer, and
`DiagramArrangement`, which lays one kind of diagram out automatically and by hand. A new kind of diagram needs a
projection, an arrangement and a `DiagramPainter`, bundled in a `DiagramNotation`.

A graphical edit will become a small text edit, so comments, formatting and Python bodies survive, and the text
outside the edited span stays byte for byte the same. The diagram is then refreshed from the text, as after any other
change. So the text is the only thing that is written, and text and diagram cannot drift apart. While the text has
syntax errors, the diagram keeps the last good state and is marked as stale. Text edits and layout changes will share
one undo history per document.

The visual information of a diagram lives in a companion file next to its source, named after the source with `.vis`
appended, such as `lamp.kstates.vis`. It is committed with the source. It holds JSON, as `VisLayoutJson` reads and
writes it, with the three keys `boxes`, `bars` and `lines`. The entries are keyed by stable identities of the elements:
a state by its name, a transition by its kind, its states and its position among equal ones, a type by its name, an
object by its key, and a link by its relation, as in `has:Lamp.bulb`. Only what has no other source is stored: the
middle of every box, the middle of the bar of every split and join, and the waypoints of every line that can be bent.
A state with inner states has no entry, because its box follows from them. The first version of the format named the
boxes `states` and the lines `transitions`, and such a file is still read.

As long as there is no companion file, a diagram is laid out automatically by the Eclipse Layout Kernel behind
`StatechartLayouter` or `StructureLayouter`. The first change by hand freezes that layout into a `VisLayout`, and from
then on `ManualStatechartLayout` or `ManualStructureLayout` draws it and nothing is arranged automatically, except that
an element that is new in the text is placed next to its siblings. In a class or object diagram, links between the
same two boxes are drawn side by side, and a label moves to the other side of its line where it would cover another
line. All stored positions are on a grid of 10 units, which the view shows while an element is
dragged. If the file and the text disagree, the text wins: an entry for an element the text no longer has is ignored and
dropped at the next save. Deleting the companion file brings the automatic layout back. The view reads the file again
whenever the workspace has scanned the folder, which is how changes made by other tools come in. The kernel needs the
small library `org.eclipse.xtext.xbase.lib` at run time, which its own package descriptions do not name, so the version
catalog lists it.

The diagram view must never take the window down, because a diagram that is partly drawn is a far smaller harm than a
window that stops reacting. So every step of the view that runs code of a notation goes through `DiagramFaults`:
measuring the texts, the layout, the drawing and every gesture. A step that fails is skipped or replaced. If the
automatic layout or the layout by hand fails, the elements are placed in a plain row. If that fails too, the canvas
stays empty. A painter draws every element on its own, so one element that cannot be drawn does not hide the others. A
failed drag is given up and nothing is saved. `DiagramTracker` catches a projection that fails and keeps the last
diagram. The first failure shows as a note over the drawing, whose text can be selected, and every failure is logged
once with its stack trace. New code in the view follows the same rule and runs through `DiagramFaults.guard`.

A companion file is a file kind and a language of its own, `FileKind.LAYOUT` and `KarpfenLanguage.VIS`. The language
colours the JSON and checks nothing. The file kind is read-only: `OpenDocumentFactory` gives its document a text from
`EditableText.readOnly`, which refuses every change except the reload from disk, so typing, pasting, commands and undo
all end in one place. The editor only draws the text greyed out, as `EditorSettings.readOnly` tells it. The project tree
shows a companion file below the model file it is named after, as `CompanionFiles` decides, and that model file can be
expanded like a folder.

### Bridges run without the IDE

The shared core of the bridges, the HTTP bridge and the MQTT bridge exist, and the IDE starts them with a run. The
runner does not start them yet. They are built in this order: plain HTTP, MQTT, starting bridges with a run, the
headless runner, and ROS. A bridge connects a running karpfen model to an external system such as an HTTP client, an MQTT broker or ROS. It
will be a plain Kotlin component with a lifecycle. It runs from the headless bridge runner on a robot or an edge box,
where no IDE is present, and the IDE configures, hosts and watches it during development. Bridges use only runtime-
protocol of the runtime modules. They run in the IDE's process or in the runner and connect to the runtime's port.

A bridge is one file in the project, `*.kbridge.json`, as `BridgeConfigFile` describes. It names the kind of bridge, its
settings and its routes. An inbound route turns a message that arrives at an address into an event, with the payload
picked out of the message by paths. An outbound route follows a property of an object, the active states of a state
machine or an event that a state machine raises, and sends a message whenever there is something new. Each route has a
flow policy, because the engine works in ticks and events expire: a queue with a limit, only the latest message, or one
message per period. `RoutedBridge` counts what each route received, delivered, dropped and failed. A scripting hook for
custom transforms may come later.

The HTTP bridge listens on `127.0.0.1:8090` unless its settings name another `host` or `port`, and it has no
authentication. A `POST` or `PUT` to the path of an inbound route becomes the event of that route and is answered with
202 at once, before the model has taken the event. A `GET` of `/` lists the paths. An outbound message is posted as JSON
to the URL of its route, and an answer outside the 200 range, a timeout after `timeoutMs` or a refused connection counts
as a failed delivery of that route.

The MQTT bridge connects to `tcp://localhost:1883` unless its settings name another `broker`, which may also start with
`ssl://` for an encrypted connection. It speaks MQTT 3.1.1 under the `clientId` of its settings, `karpfen-bridge` by
default, and a broker allows one connection per client id, so two MQTT bridges on one broker need different ones. The
`from` of an inbound route is a topic filter that may use the wildcards `+` and `#`. The payload of a message is read as
UTF-8 text. Subscriptions and published messages use the `qos` of the settings, 1 by default, and `retain` makes the
broker keep the last published value of a topic. If the connection breaks, the client connects and subscribes again on
its own, and outbound messages fail in the meantime.

Every run of a project with bridge files asks which of them to start, in a dialog with one box per file. The boxes of
the last run are ticked, because the choice is stored in the project file as `bridge` lines. Cancel cancels the run.
`RunBridges` in ui-workbench holds the bridges of one run. It builds them when the session is launched, starts them
together once the environment runs, and stops them when the environment stops, when the session fails, and when the
run is closed. A file that cannot be read, has a problem, or is of a kind the IDE cannot run gives a bridge that
failed, and the others start all the same. The Log pane lists the bridges of the selected run below the deployment,
each with its state and how many messages it handed on, and names every route that dropped or failed one. An MQTT
bridge whose file names no `clientId` gets one from the name of its file, so two MQTT bridges of one run do not push
each other off the broker.

A bridge inside the IDE has no client of its own on the runtime. `SessionModelLink` puts it on the session of the run.
Its events go through the injector of the session and are checked like events sent by hand. It hears the domain events
the session observes. Changes of objects and of active states are read from the observed model, so a route that
follows a property or a state machine says the current value once at the start and then every change, but only while
the run observes the model. With observation off those routes stay silent, and event routes keep working.

In the IDE a bridge file is never edited as text. A form reached from the Run menu writes it, and other tools may change
it, so reading is tolerant and names what is wrong in words. The form does not exist yet, and until then a bridge file
opens as text like any other file. ROS will use the rosbridge protocol, which is JSON over WebSocket, through the Ktor client, so no
native library is needed. A native ROS transport can be added behind the same interface if latency demands it. The
runtime freezes its domain listeners when an environment is activated, so a bridge that listens to domain events must be
registered while the environment is deployed.

### Module boundaries are enforced by the build

A layering that lives only in documents erodes. So the allowed dependencies between modules are code in build-logic,
and the architecture tests add the import rules that a dependency matrix cannot express. The core modules see only
each other. runtime-protocol depends on no IDE module, so that bridges can use it without the core.

### Coroutines and flows

All background work runs in coroutines with structured scopes. State is published as flows, and the UI collects them.
Types that wait or delay take their dispatcher as a parameter, so tests run them on virtual time and never sleep.
Production defaults for dispatchers, clocks and random sources appear only in options values and public factory
functions.

Each open document has its own scope, and so has each runtime session. Closing one cancels all of its work. Each
`StateFlow` has exactly one writer, which keeps its mutable flow private. A `CancellationException` is always
rethrown. Cleanup that must finish, such as stopping an environment, runs without cancellation but within a time
bound. There is no framework for dependency injection. If the hand-written wiring grows unwieldy, one can be added
later.

### Environment descriptors are IDE-owned JSON

An environment descriptor (`*.kenv.json`) says which metamodel, events, model and state machines make up a runnable
environment, and how to configure it. It is JSON so that no new language is needed. The IDE edits it with full
language support: highlighting, completion, hover and validation against a schema derived from
`EnvironmentDescriptor`.

JSON is not one of the dsl-tools languages. The IDE reads descriptors with a tolerant JSON parser of its own in the
`json` package of core-document. A JSON library reads only valid text and loses the position of each value, but the
editor needs a tree of every text, also of a broken one while the user types. The workspace then decodes the
descriptor into `EnvironmentDescriptor` with kotlinx.serialization. A form editor for descriptors could later be built
on the same typed descriptor and schema.

### dsl-tools output stays off the console

dsl-tools logs through SLF4J. Its lines go to their own log file and to the Language Tools Output, not to the console
or `ide.log`. Only its warnings and errors are forwarded to `ide.log`. This keeps the IDE's log readable while the
language tools' output is still there when needed. The readers report every problem in a file as a diagnostic, so
they only log a read that fails, which is also reported as a problem.

The Language Tools Output is a tool window below Problems, opened from the View menu. It shows each line with its
level, its logger, the file dsl-tools was reading and the message, follows new output and can be cleared. When
warnings or errors arrive while it is hidden, the status bar shows `Language tools: n`, and a click opens it.
`RingOutputBuffer` holds the lines. The app's logging writes into it as an `OutputSink`, and the workbench reads it as
an `OutputBuffer`. The command-line tool keeps the dsl-tools log off entirely unless `--verbose` is given.

### Required repositories are submodules

karpfen-dsl-tools and karpfen-runtime are git submodules on branches that carry the changes the IDE needs. Today
karpfen-runtime points at a commit of its branch `feature/ide-support` and karpfen-dsl-tools at one of
`feature/editor-support`. A checkout then builds without a published artifact and without anything outside the
repository. It also pins the exact runtime source the IDE's client is tested against. A submodule commit must be
pushed before the commit that points to it, or a fresh clone cannot check it out.

### The runtime client speaks the runtime's IDE-support API

The IDE talks to the runtime only over its HTTP and WebSocket API, like any other client, so the IDE and the runtime
keep their own versions. The runtime gained a model snapshot, a revision on every push, a subscription to the whole
model and domain events for the IDE. runtime-protocol is a typed client of that HTTP and WebSocket API, and
runtime-session builds deployment, observation and event injection on it. The client keeps a catalogue of routes, wire
names and rejection texts, and anchor tests check each entry against the runtime's source.

### A deployment uploads the saved files

A run deploys what is on disk, never unsaved editor text. The Run action saves every open file first. The check for
errors before the upload reads the diagnostics of exactly the analyzed texts that are uploaded, and a file that was
edited again in the meantime blocks the run. The descriptor holds the whole deploy configuration, so a run can be
repeated without the IDE's state. Every deployment sends the tick delay and the event time to live, so the runtime's
own defaults never apply. A stopped environment is final. Running again creates a new one.

### Fakes with fixed answers, and real runtimes in throwaway containers

Tests against a fake that simulates the runtime test the fake, not the runtime. So the fakes only return fixed
answers, and every scenario that needs real behaviour runs against a real runtime. Those live tests run on demand, in
containers that are created for the run and removed afterwards.

### The runtime runs in Docker or at a URL

The IDE never runs the runtime as a local Java process. It either hosts it in its own Docker container, one per
workspace, or connects to a runtime that someone else started at a URL. The container image is built on first use
from the runtime sources that are bundled into the IDE at build time. So the image always matches the client, and no
published image or registry is needed. The image holds Python 3 with numpy, scipy, sympy, pandas, regex,
python-dateutil and chardet, which `EVAL` blocks can import. The container is created without automatic removal, so
its log stays readable after it stopped, until the IDE removes it.

### Time-budget tests are written but not run

Performance targets are written down as tests tagged `budget`. They never run, because the wall clock on developer
machines and build servers is too unreliable to judge them. Performance work comes later, with the tests as a starting
point.

### Documentation lives in KDoc

Markdown documents about code drift away from it. So the detailed documentation is KDoc on the declarations, and the
few markdown documents only explain the big picture and point to types. The architecture tests enforce the style.

### Product choices

- A deployment listens to every event domain that the state machines use, so the run shows all their domain events.
  The events file declares event types, not domains. So a domain counts as declared when the events file declares at
  least one of the events that the state machines use in it. The injector lists the declared domains first and marks
  the others as used only.
- The default tick delay is 50 ms and the default event time to live is 3000 ms (`EnvironmentDefaults`).
- Observation is a switch that can be turned on and off while a run is live. When it is off, Pull fetches one snapshot.
- Code completion is Ctrl+M on every platform, also on macOS with the physical Control key. Windows also binds
  Ctrl+Space. macOS and Linux leave Ctrl+Space alone, because it switches input sources there.
- The event injector edits a JSON payload that is prefilled with a template of the event's payload type.
- A model whose objects reference each other in a loop is not deployed. The runtime cannot write such a model yet.
- The command-line tool only validates. It has no deploy command yet.

## Behaviour worth knowing

### Docker resources and how to remove them

The IDE labels everything it creates. Read `RuntimeLabels`, `RuntimeImage` and `WorkspaceContainer` in the file
`DockerNames.kt`. Every container and image carries `io.karpfen.ide.runtime=true`. Images also carry the content hash
and the commit of the bundled runtime. Containers also carry the content hash of their image, the workspace path and
the hash of that path. The image tag is
`karpfen-ide/runtime:` followed by the first 12 hex digits of the bundle's content hash. The container name is
`karpfen-ide-runtime-` followed by the first 10 hex digits of the SHA-256 of the normalized workspace path. The
container binds port 8080 to an ephemeral port on 127.0.0.1. It is not removed automatically when it stops.

The host only lists, stops or removes what carries the label, and containers only of its own workspace. On start it
adopts a matching running container of the workspace and removes outdated ones. After building a new
image it removes the older unused images. The configuration `application.conf` is copied into `/app` as a tar archive
before the start, so no folder is mounted. To remove everything by hand:

```bash
docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)
docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)
```

The image build also leaves the unlabelled build-stage image and the base images `eclipse-temurin:21-jdk` and
`debian:trixie-slim`. The runtime's `Dockerfile` has a build stage and a runtime stage, and only the image of the last
stage gets the labels. Find the build stage with `docker images --filter dangling=true`. That list also shows the
untagged images of other builds, so remove only those created at the time of a runtime build. Remove the base images
by name if nothing else uses them.

### Starting and stopping the runtime host

Read `RuntimeHosts` and the Docker host behind it. A mutex allows one start at a time. A call that arrives during a
start joins it and also gets its failure. Only a later call starts again. A start that fails after it created or
adopted a container removes that container. Stop cancels a start that is under way, and the callers waiting for it
get the problem `CONTAINER_FAILED` that says the runtime was stopped before it was ready. After close, nothing starts,
and every call gets that same answer.

The host polls the health endpoint every 500 ms for up to 120 s, because the JVM and the first Python start of the
runtime are slow. A watcher inspects the container every 5 s. It marks the host failed with `CONTAINER_DIED` when the
container stopped or is gone, or after 3 failed inspections in a row. The message names the container's exit code when
it has one. The runtime keeps its environments only in memory, so nothing of the runs on a died container can be
recovered. They have to be run again. A single failed inspection is tried again. The
watcher changes the status only while it is still the same running status, so it never overwrites a stop or a newer
start. The next start begins afresh, so it adopts the container if it still runs, or creates a new one. Stopping
waits up to 10 s, and closing is bounded at 15 s. When the runtime turns unhealthy, the last 20 log lines go into the
message. `HostLog` replays up to 2,000 lines to a new collector.

The Docker endpoint comes from `DOCKER_HOST`, then the current Docker context, then the platform default, as the
docker command line finds it. Only `unix://` and `npipe://` endpoints are accepted. A `tcp://` endpoint fails with
`ENGINE_NOT_LOCAL`, because the runtime would be published on another machine.

### Shutting down

Read `RuntimeHolder`, `RuntimeState` and `IdeRunState`. On exit the IDE first shuts down every live session, for at
most 5 s. Sessions still open after that are closed at once. Then the host stops its container. The whole shutdown is
bounded at 15 s. When it takes longer than 1 s, the window shows a note that the runtime is stopping.

A session has two ways to end. `RuntimeSession.shutdown()` waits for a running deployment to end and stops the
environment if it is activated, running or halted. `close()` returns at once. While the deployment runs, it cancels
the deployment, and the deployment's cleanup stops an environment that was activated already. Otherwise it leaves the
environment on the runtime as it is. The `EnvironmentLedger` records what was left: activated, running, halted or
stopped.

### Runs in the Runtime tool window

Read `RuntimeState` and `DescriptorResolver`. Running a descriptor again cancels its pending runs and shuts down its
live ones. Their entries stay in the list as stopped. Changing the runtime settings stops the live runs and replaces
the host. The new host becomes current before the old one closes. Stop Runtime also stops the live runs first. The
tool window keeps 5,000 log lines, 10 runs and 500 events per run.

Run is Shift+F10 and Stop is Ctrl+F2, or Cmd+F2 on macOS. Run picks the descriptor in this order: the one it was
given, the one in the active editor, the only one in the workspace, or the one the user picks, with the last choice
preselected. Without any descriptor it shows an error.

`InjectorState` prefills the payload with the template of the event's payload type. It checks the payload on every
edit and replaces it with the new template when another event is chosen.

The Model, Events and Log panes of a run are shown next to each other, so the effect of an event is in sight while it
is sent. The bottom tool windows take the whole width of the window. The Problems tool window is closed whenever it
has nothing to show, and `SubStateBindings` enforces that. Its label in the status bar carries the error icon while
the workspace has errors.

Run from the menu or by its shortcut gives the Runtime tool window three quarters of the workbench's height. The
layout view reports that height to `LayoutState`, which is how a state without Compose knows it.

Two zoom controls change the project settings `uiScale` and `editorFontSize`, within the limits of `Zoom`. The one
for the whole window sits in the status bar, the one for the editor's font in a bar below the editor.
`Workbench` applies `uiScale` by providing a density that is the window's density times the scale. So everything
scales together, the text with the rows, icons and gaps around it. Scaling only the font would make text outgrow
the rows of fixed height. The editor is left out: `EditorArea` draws it at the window's own density, so the two sizes
are independent.

### The engine trace in the log

The IDE's container runs the runtime with engine tracing on and the trace printed to the console, as
`RuntimeConfiguration` writes it. The trace is the only place where the runtime tells which transition fired. The
engine writes several trace lines on every tick of every state machine, even when nothing happens. `RuntimeHostBinding`
drops those lines before they reach the log, as `EngineTrace.isTickNoise` decides, or they would push everything else
out of the 5,000 lines within seconds. The simplified log of `StatechartLog` shows the remaining trace lines in a few
words each, and every error line. A runtime reached by URL has no log in the IDE, so it has no simplified log either.

### Deployment steps and problems

Read `DeployStepKind` and the steps in the `deploy` package of runtime-session. A deployment checks the runtime,
creates the environment, connects, uploads the metamodel, the events and the model, attaches the state machines, sets
the tick delay and the event time to live, listens to the domains, activates, starts observing, starts and confirms
the start.

The session connects right after it created the environment. The runtime never acknowledges the WebSocket handshake,
so the many calls before activation are what separates the handshake from the first push. Problems carry a code, such
as `deploy-unsaved-file`, `deploy-reference-loop`, `engine-halted` or `observation-out-of-sync`. The descriptor
template still writes an `observe` key. Deployment ignores it and reports `deploy-observe-superseded` as information.

The runtime reads uploads with its older parser, so a text that the IDE reads cleanly may still be rejected without a
position, or read differently. A syntax error in an upload is either accepted in part or fails with an internal error
of the runtime. Three things make this rare and visible. The run is blocked on the IDE's own findings before anything
is uploaded. `FailureLocator` maps the known texts of the runtime's parser to the declaration they name. The first
snapshot is compared with the model the IDE analyzed.

### Reference loops

The runtime writes a known object nested inside the object that knows it. A loop of references therefore cannot be
written. The runtime now throws instead of overflowing its stack, and its snapshot answers 409. The IDE finds such
loops before it deploys and blocks the run with `deploy-reference-loop`. Read `ReferenceLoops`.

### Observation

Read `ObservationReducer`. A mirror starts from a snapshot and then applies pushes in revision order. A gap in the
revisions or a reconnect triggers a fresh snapshot. While observation is off, pushes are ignored. When the environment
stops, the session takes one final snapshot. The runtime answers a snapshot only at a tick boundary and waits up to
10 s for one.

Every Pull, every switch to observation on, every resynchronization and the confirmation of a start read a snapshot
of the whole model. On a large model, or with long Python calls in a tick, these get slow, and a snapshot that finds no
tick boundary within 10 s is refused. After its retries the session reports `observation-out-of-sync`.

The runtime takes injected events on a thread of their own, next to the engine's tick loop. So two state machines may
react to one event in different ticks.

### What the runtime client copes with

Read `DefaultRuntimeConnection`, `LifecycleRules` and `RuntimeJson`.

- The client checks that the runtime supports the IDE before it does anything else.
- It refuses calls that the runtime would mishandle in the environment's current phase, and sends nothing.
- The runtime writes `NaN`, `Infinity` and `-Infinity` as bare literals. `RuntimeJson` accepts exactly these and
  nothing else that is not JSON.
- The runtime keeps pushing to the first socket of a client id. So before a reconnect the client closes the old socket
  normally. A half-open old socket still swallows pushes until the runtime's ping drops it, which shows up as a
  revision gap. The model then resynchronizes, at worst about 75 s late. Domain events lost in that time are not
  recovered.
- The client pings every 20 s (`KtorTransportOptions`), so it notices a dead runtime before the runtime's own 60 s
  ping would.
- Forcing a state with `forceState` makes the runtime discard all queued external events of the environment. At the
  next tick it also clears the queued events of the domain named like the forced machine's object key. Deployment
  warns with `deploy-domain-is-attachment` when such a domain exists.
- The first snapshot of a deployment is compared with the model the IDE analyzed. Each object that differs gets
  the warning `deploy-model-differs` at its declaration.

### Keyboard handling

Read `ProxyTextInput`, `LayoutAwareStrokes` and `defaultKeyBindings`. The editor gets its text through a hidden text
field at the caret, so input methods, dead keys and the accent menu work. When a key press was handled as a command,
the character it would type is swallowed. A stroke goes to completion first, then to the editor's keymap. Menu
shortcuts only fire for strokes nobody consumed. The macOS screen menu bar sees key equivalents first, as Java key
events. The menu bar is the only place that dispatches window shortcuts, and each menu item has one stroke. The menus
and every editor take their strokes from the same keymap, so they always agree. Tab and Shift+Tab never move the focus
out of the editor.

`LayoutAwareStrokes` maps punctuation strokes by the character they type, so Cmd+Shift+7 on a German layout acts as
Cmd+/. Headless tests cover this with synthesized events only, so Cmd+/ on a real German Mac layout is checked by hand.
On a German Windows layout, Ctrl+Shift+7 carries no character, so it stays a press of the 7 key and Toggle Comment
cannot be reached that way. This is a known limitation.

On macOS the app sets `apple.eawt.quitStrategy` to `CLOSE_ALL_WINDOWS`, so Cmd+Q, Quit in the application menu and
logging out run the window's close request with the unsaved-changes dialog. The default strategy would exit at once.
A `-D` system property given on the command line wins over the defaults the app sets.

### Comments in state machines

Python code in an `EVAL` body uses `#` for comments. The editor warns about `//` there with `eval-double-slash`.
Toggle comment uses `#` when the toggled lines lie inside an `EVAL` body whose opening brace comes before them. Read
`LineComments.markerAt`.

### Positions that survive edits

Read `EditableText.mapRange`. It is the one rule for moving a range through later edits. The start maps with right
bias and the end with left bias. A range that would turn inverted collapses to an empty range at the mapped end. An
empty range stays empty. It returns null for a range from another text or one older than the kept history of 1000
versions. It is thread-safe.

Typing within 1 second coalesces into one undo step. The undo history itself has no limit. dsl-tools reports UTF-16
offsets, which `ReadResults` carries over and clamps to the text. A read that fails becomes one `reader.failure` error
at the start of the file. `ExternalDiagnosticsAnchors` moves diagnostics from the project analysis along with every
edit.

### Parsing and analysis

Read `DocumentSessionConfig`, `WorkspaceOptions` and `NestingLimit`. Open documents are parsed in syntax-only mode. Only
the project analysis reads files with dsl-tools, and only files without syntax errors. So each file is read once, the
IDE and the command-line tool agree, and opening or closing a file changes no finding. Events files and models are
read only against a metamodel that was read without errors, which avoids follow-up errors. `RememberedReads` keeps the
last read of each file, so a file is read again only when its text or its metamodel changed. An unreadable unchanged
file is therefore read and logged once, while its findings are reported on every analysis.

The parse waits 120 ms after the last edit, the file watcher 200 ms and the project analysis 300 ms. The latest
analysis request wins and cancels a running one. Blocks nested deeper than 300 levels get `nesting-too-deep` instead
of a parse that could overflow the stack. Both the IDE's parsers and the dsl-tools readers follow more than twice that
depth on the default thread stack, so a text within the limit always parses and reads the same way.

dsl-tools fills the event types of a context with the domain's base types too. The IDE takes the event types only
from the `type` declarations of the events file itself.

### Files on disk

Read `SourceLoader`. Line separators are normalized when a file is loaded, and the dominant one is restored when it is
saved. A byte order mark is removed on load and written again on save. A file that is not valid UTF-8 gets the warning
`malformed-encoding`. A file with mixed line separators gets the information `mixed-line-separators`. Both findings
follow the disk: a successful save clears them and a reload replaces them.

Read `AtomicTextFileStore`. Saving writes a hidden temporary file next to the target, forces it to the disk and then
moves it over the target in one step. So a crash never leaves a truncated file. The file keeps its permissions, and a
symbolic link keeps pointing to its target. A read-only file is never replaced, and a deleted parent directory is
created again.

Read `OpenDocument` and `DiskSynchronizer` for changes made outside the IDE. A change on disk to a document without
unsaved edits reloads it as one undoable change. A document with unsaved edits gets a banner with Reload and Keep
mine, and a deleted file one with Close and Keep mine. Content that equals what the document last saw changes
nothing, which is how the watch event of the IDE's own save is recognized. When the file watcher fails, watching ends
with a warning in `ide.log`. After that, changes on disk show up only through a refresh, which file operations in the
Project tree run.

### Descriptors and modeling contexts

Read `DescriptorValidator` and `ContextResolver`. A known key that appears twice in a descriptor is an error, because
the JSON decoder would silently keep the last value while other JSON readers take the first. An unknown key is only a
warning, so a descriptor written for a newer runtime still opens.

Each descriptor defines one context of the files it names. A descriptor also covers its whole directory, and the
files there that it does not name are loose files. Every directory without a descriptor forms an implicit context of
the karpfen files directly inside it that no descriptor names. Its metamodel is the one `.kmeta` file that is not
`EVENTS.kmeta`, and its events file is `EVENTS.kmeta`, matched ignoring case. With several metamodels, each gets an
information, the first in the case-insensitive order of the tree is used, and the others become loose files. Loose
files are tokenized and parsed, but dsl-tools never reads them. The IDE and the command-line tool follow the same
rules.

`ModelingContext` includes the typed descriptor in its equality, so editing a value such as the tick delay starts a
new analysis without reading any file again. The descriptor's completion of `attachedTo` and the template of new
descriptors rank the objects of the model by one rule, `AttachmentCandidate.rank`.

### Moving the runtime submodule

Read `RuntimeSourceAnchorTest`. Moving karpfen-runtime to another commit makes the anchor tests fail exactly where the
runtime changed. Then update the catalogue, the defaults of `FakeRuntime` and the runtime goldens together with the
client. Run the live suite with `runtime/live-runtime.sh test`. Only then update the anchors.

### Common changes

- A new module needs an entry in `modulePaths` of `settings.gradle.kts`, a rule in `ModuleDependencyRules` and an
  entry in `KarpfenModules` of the architecture tests. It must apply `karpfen.kotlin-jvm`, directly or through another
  karpfen plugin, or the settings fail. The first rule of `ModuleDependencyRules` that matches a module applies.
- A new runtime endpoint starts on the runtime's branch with IDE support. On the IDE side it goes into the catalogue
  (`RuntimeRoute`, `WireNames`, `RuntimeText`) and gets a function of `RuntimeApi`. If it concerns one environment, it
  also gets a function of `RuntimeEnvironment` with its verdict in `LifecycleRules`. It needs the goldens, an anchor in
  `RuntimeSourceAnchorTest`, a fixed answer in `FakeRuntime` if a test needs one, a row of `RuntimeContract` and a live
  run.
- A new descriptor key goes into `EnvironmentDescriptor`, with a `@KeyDoc` that explains it. The schema, validation,
  highlighting, completion and hover follow from it. Deployment and `EnvironmentTemplate` must be updated by hand.
- A new editor action needs an id and a title in `EditorActionIds` and `EditorActionTitles`, an implementation listed
  in `defaultEditorActions`, and a binding in `defaultKeyBindings` if it has a shortcut. The workbench registers it as
  a command in `EditorCommands`, and `IdeMenus` places it in a menu.
- A new language feature belongs in core-lang, behind `LanguageServices`. Its provider goes into the language's
  `LanguageFeatures`, and the editor only renders its result.
- A new language needs one `LanguageSupport` class in the `registry` package of core-document and an entry in
  `defaultLanguageRegistry`, and its editor features as a `LanguageFeatures` in `karpfenLanguageFeatures`. A test of
  core-lang checks that every language has features. `KarpfenLanguage.fromFileName` decides which files it owns.

## Open points

### In the runtime

- There is no channel that reports engine errors to the client. The IDE only sees that the engine halted or went
  silent and points to the runtime log.
- Environments cannot be deleted, so they pile up in a long-running runtime. Access keys never expire, and events
  sent to a stopped environment are queued anyway.
- The engine skips the entry block of a state that a join leaves in the next tick. The test for it in
  `ObservationScenarios` is disabled until the runtime is fixed.
- Known objects are written nested instead of by reference, so reference loops cannot be deployed.
- `NaN` and `Infinity` are written as bare literals, which is not JSON. What they should become is undecided.
- One broadcaster serves all clients, so one slow client can stall the others. A queue per client would fix it.
- The handshake is never acknowledged. Several sockets with one client id get pushes only on the first.
- Domain listeners and observation registrations cannot be removed while an environment runs. So domain events keep
  flowing while observation is off. Whether observation off should silence them too is undecided.
- Pushes still queued when an environment stops are dropped.
- A macro that changes an existing object sends no push. Setting a scalar that was never written does send one.
- The runtime pushes nothing when a transition fires or an event is consumed, so a live view cannot show either.
- Starting a stopped environment restarts it from the initial states over the changed model. The stop closed its
  sockets, so clients must connect again. The runtime does not document this, and the IDE never does it.
- There is no version endpoint, no published image, no configuration through environment variables and no
  authentication. Access keys are predictable, and the runtime prints them on its console.
- The runtime reads uploads with the dsl-tools 1.0 parser from its own nested submodule, so upload errors carry no
  positions.
- The runtime's documentation of `eventConsumptionOnFire` does not match what the engine does.
- A JSON event payload with a repeated key or a scalar of the wrong type silently becomes null as a whole, and every
  relation in a JSON payload creates new objects, `knows` included. The event still fires, without its payload. The
  injector's checks in `PayloadDiagnosticCodes` mirror the runtime's converters and catch these cases before an event
  is sent.
- State names are global in the runtime, and its lookups take the first match depth-first. A nested state that shares
  the name of a top-level state therefore makes the reported stack ambiguous. `StackSegmenter` explains the effect.
- The runtime never reads the type a state machine names after `ATTACHED TO`. It treats `_` as an ordinary object key
  and keeps the last of two objects with the same key. The deploy checks report a differing type and a repeated key
  before a run, and they treat `_` as a key the way the runtime does.
- The branch with IDE support changed what the runtime sends and accepts. An environment is activated at most once and
  a second activation is refused with 409, and pushes carry revisions and domain events. Nobody has checked yet whether
  other clients of the runtime rely on the old behaviour. That matters before the branch is merged.

### In the IDE

- When the watcher gives up on a container that still runs, the next start adopts it and follows its log from the
  container's start again. The Log pane then probably shows earlier lines twice. This follows from the code and has
  not been seen yet.
- With tracing on, the runtime also writes the whole trace, tick lines included, to a file in the container. The file
  grows until the container is removed, which happens when the IDE closes the folder.
- An exception while the window is composed or drawn freezes the window. It is written to the log, but no error dialog
  appears. The diagram view is the exception: it catches its own failures, as the section on diagrams describes. The
  rest of the window has no such guard.
- The descriptor template still writes the `observe` key, which deployment ignores.
- The descriptor keys `runtime.image`, `runtime.pythonPackages` and `bridges` are accepted but ignored. So `EVAL`
  blocks can import only the Python packages of the runtime image.
- The runtime container runs without limits on CPU or memory.
- After a restart the IDE does not adopt the environments it left on a runtime.
- Deployment problems are shown in the run, not in the Problems view.
- The command-line tool cannot deploy.
- There is no folding UI and no find-usages UI.
- The theme menu has no entry for following the system. The zoom controls have no commands and no shortcuts yet.
- The undo history has no limit.
- Whether files next to a descriptor but not named by it should form an implicit context is undecided.
- There is no Refresh command. After the file watcher failed, changes made outside the IDE show up only when a file
  operation in the Project tree refreshes the workspace.
- On a German Windows layout, Ctrl+Shift+7 does not reach Toggle Comment.
- The descriptor key `bridges` is ignored. A run offers every bridge file of the project, whatever the descriptor
  names.
- The ROS bridge and the bridge runner are stubs. The form that edits a bridge file does not exist yet. A bridge in
  the IDE hears changes of the model only while the run observes it. A password in the settings of a bridge file is
  stored as plain text in the project. An embedded runtime and a language
  server do not exist yet. Their paths `runtime/runtime-embedded` and `tools/lsp-server` are planned but not in the
  settings, and `lsp4j` in the version catalog is unused so far. The language server is meant to put core-lang behind
  the Language Server Protocol for other editors.
- tools/cli declares a dependency on runtime-session for a deploy command that does not exist yet.
- The diagrams cannot change the text yet and do not show a running model: neither the active states in the
  statechart nor the current values in the object diagram. The lines of splits and joins have no waypoints. The view
  mode of a file is not stored in the project file. The class diagram shows the types of one file only, so a relation
  to a type of another metamodel file is a row and not a link.
- There is no rename refactoring, no command that reformats a file and no split editor. dsl-tools 2.0 has printers
  for the three languages, but the IDE does not use them yet. The caret model holds a list of carets, but the editor
  offers no way to add a second one.
- dsl-tools 2.0 checks the types, properties, relations, references and numbers of a model, the number of initial
  states and the arity of actions. Still missing are checks for repeated names and keys, transitions to unknown
  states, the number of targets of a `SPLIT` and of sources of a `JOIN`, the existence and arity of macros, the model
  paths in actions and `EVAL` bodies, and unreachable states. These checks belong in dsl-tools.
- Stepping the engine tick by tick and breakpoints on transitions are still to come. They need a step mode in the
  runtime's engine.
- Performance work is still to come, for example on the model mirror and on snapshots of large models. The tests
  tagged `budget` hold the targets.

### In the build and on other platforms

- There is no continuous integration. The build and the tests run only on the developer's machine. A build needs
  the nested karpfen-dsl-tools of karpfen-runtime for `bundleRuntimeSources`, so a clone must be recursive.
- Gradle prints a deprecation warning about `Configuration.setVisible`. It most likely comes from a plugin applied in
  app/ide, but that has not been confirmed. The warning says the build will not work with Gradle 10. Check it again
  when the Compose Gradle plugin is updated.
- Only macOS has been built and tried. Linux and Windows are not built at all.
- Which platforms to ship, and signing for macOS and Windows, are undecided.
- A Dockerfile with separate dependency layers would make image rebuilds faster. This is undecided.
- The image build leaves base images and a build-stage image behind.
- When the runtime repository publishes an image, pulling it instead of building it needs a new decision.
- Podman, Colima and Rancher Desktop offer the Docker API on a local socket and should work through `DOCKER_HOST` or a
  Docker context. Nobody has tried them.
- Whether the runtime's IDE-support branch should be merged into its main branch is undecided.
