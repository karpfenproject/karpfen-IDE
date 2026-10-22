# karpfen IDE

karpfen IDE is a desktop IDE for the karpfen DSL ecosystem: KMeta metamodels (`.kmeta`), KModel instances
(`.kmodel`) and KStates hierarchical statecharts (`.kstates`) with embedded Python in their `EVAL` blocks;
environment descriptors (`*.kenv.json`) tie them together. It aims at textual editing with full language support,
blended textual and graphical notations, deploying to and experimenting with a local karpfen runtime, bridges to MQTT
and ROS, and live visualization of state machines and models. It is written in Kotlin on the JVM with Compose
Multiplatform for Desktop and builds on [karpfen-dsl-tools](https://github.com/karpfenproject/karpfen-dsl-tools) as its
language core.

**Status: the text editor (phase 1) is complete.** The IDE opens a folder of karpfen files and edits them with syntax
and semantic highlighting (including the Python of `EVAL` bodies), live syntax diagnostics, the diagnostics of a
karpfen-dsl-tools analysis of each modeling context, completion, go to definition, hover, outline, problems, find and
replace, and a project tree with file templates; `karpfen validate` checks a folder from the command line. The work
continues in the [increments after phase 1](docs/plans/increments-after-phase-1.md); diagrams, the runtime and the
bridges are stubs so far.

## Documentation

- The [codebase guide](docs/codebase-guide.md) is the map of the code: where each feature lives, which types to read
  first, and the conventions for design, documentation and tests. Technical detail lives in the KDoc of the code.
- The [architecture decision records](docs/adr/README.md) record each architectural decision on its own.
- The [architecture proposal](docs/architecture-proposal.md) is the accepted design record the decisions came from.
- The [plan of the increments after phase 1](docs/plans/increments-after-phase-1.md) describes the current work.
- The [manual smoke test](docs/manual-test.md) covers what the automated tests cannot.
- The [phase-1 records](docs/history/phase-1/README.md) keep the planning and status documents of phase 1 for
  reference.

## Build, run and test

### Requirements

- A JDK 17 or newer to run Gradle; JDK 21 is recommended. Compiling, tests, the `run` tasks and packaging use the JDK
  21 toolchain, which Gradle takes from the local installations or downloads through the foojay resolver. The desktop
  application is pinned to that toolchain too, so the IDE and its packaged runtime are JDK 21 even when Gradle runs on
  an older JDK.
- Network access to the Gradle Plugin Portal, Maven Central and Google Maven, and, for downloading a JDK, to the
  foojay discovery API (`api.foojay.io`) and the JDK vendor's download host.
- karpfen-dsl-tools, either as a checkout next to this repository or in your local Maven repository; see
  [karpfen-dsl-tools resolution](#karpfen-dsl-tools-resolution) below.

### Gradle tasks

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles every module and runs the unit tests, the architecture tests and the module dependency check. |
| `./gradlew :core:core-lang:test` | Runs the tests of one module, the quick loop while working on it. |
| `./gradlew :app:ide:run` | Starts the IDE with an empty state that offers Open Folder… and the recent folders. |
| `./gradlew :app:ide:run --args=<folder>` | Starts the IDE on a folder of karpfen files. |
| `./gradlew :tools:cli:installDist` | Builds the command-line tool's start script, `tools/cli/build/install/karpfen/bin/karpfen`. |
| `./gradlew :tools:cli:run` | Runs the command-line tool, which prints its usage. |
| `./gradlew :bridges:bridge-runner:installDist` | Builds the start script of the headless bridge runner, a stub that prints its usage. |
| `./gradlew :app:ide:packageDistributionForCurrentOS` | Builds the native installer for the current system: dmg, msi or deb. |

The packaging setup, including why the macOS app image and dmg carry the version 1.0.0 while the other installers
carry 0.1.0, is documented in `app/ide/build.gradle.kts`. The dmg is written to
`app/ide/build/compose/binaries/main/dmg/`.

### Tests

`./gradlew build` runs the automated tests. Each module's test report is written to
`<module>/build/reports/tests/test/index.html`. Time-budget tests, which compare wall-clock durations with a limit,
carry the JUnit tag `budget` and are never run, as the
[decision that time-budget tests are not run](docs/adr/0011-time-budget-tests-are-not-run.md) records. The
[testing conventions](docs/codebase-guide.md#testing) of the codebase guide describe the fixtures, the end-to-end tests
and how to test on virtual time.

Before a release, run the [manual smoke test](docs/manual-test.md): about 12 minutes at a real machine with a German
keyboard layout. It covers what headless tests cannot: the native menu bar, typing on a real keyboard layout, focus and
mouse handling, changes on disk and quitting.

### Running the IDE

`./gradlew :app:ide:run --args=<folder>` opens a folder of karpfen files. To try it on the example files of the karpfen
repositories, work on a copy:

```bash
cp -R core/core-document/src/testFixtures/resources/examples /tmp/karpfen-examples
./gradlew :app:ide:run --args=/tmp/karpfen-examples
```

Every directory with karpfen files forms a modeling context of its `.kmeta` metamodel, `EVENTS.kmeta`, `.kmodel`
models and `.kstates` state machines, unless an environment descriptor (`*.kenv.json`) names the files of a context
explicitly. Text files on disk are the single source of truth, and changes made by other tools are picked up; on macOS
the JDK's file watcher polls, so this takes up to about 10 seconds.

Settings (`settings.json`: recent folders, theme, font size, window bounds, tool-window sizes) and the log
(`logs/ide.log`) live in `~/.karpfen-ide`. The environment variable `KARPFEN_IDE_HOME=<dir>` moves both, for example for
a throw-away run.

For a launch smoke test, `KARPFEN_IDE_EXIT_AFTER_MS=<ms>` makes the IDE close itself after that many milliseconds and
exit with status 0. After this run, the log contains no `ERROR` line:

```bash
KARPFEN_IDE_HOME=/tmp/karpfen-home KARPFEN_IDE_EXIT_AFTER_MS=6000 ./gradlew :app:ide:run --args=/tmp/karpfen-examples
grep ERROR /tmp/karpfen-home/logs/ide.log
```

### Command-line tool

`karpfen validate` checks a folder like the IDE does: the syntax of every file, the dsl-tools analysis of every
modeling context and the environment descriptors. It prints one line per problem, with one-based positions and paths
relative to the folder, and only the report: what dsl-tools prints while it analyzes is kept out of it.

```bash
./gradlew :tools:cli:installDist
tools/cli/build/install/karpfen/bin/karpfen validate <folder> [--format text|json]
```

A problem looks like this:

```text
karpfen-runtime/single_robot_example/cleaning_robot.kstates:8:13: error: extraneous input 'ENTRY' expecting '{' [syntax-error]
```

The exit status is 0 without errors (warnings and infos are printed but allowed), 1 if an error was found, 2 for an
invalid command line or a missing folder, and 3 if the validation itself failed. `ExitCodes` documents the statuses and
`ReportFormatter` the text and JSON formats.

## karpfen-dsl-tools resolution

The IDE depends on `io.karpfen:karpfen-dsl-tools` and resolves it in one of two modes, as the
[decision on how dsl-tools is resolved](docs/adr/0010-dsl-tools-resolution-modes.md) records; the KDoc in
`settings.gradle.kts` documents the rules.

1. **Composite build**, for working on both repositories at once. The build looks for a dsl-tools checkout at the path
   given with `-PdslToolsPath=<path>`, else at `dslToolsPath=<path>` in a git-ignored `local.properties` in the
   repository root, else at the sibling directory `../karpfen-dsl-tools`. If that directory holds a Gradle build, it
   is included and replaces the artifact; the build log shows `karpfen-dsl-tools: composite build from <path>`. On
   Windows, write the path in `local.properties` with forward slashes (`dslToolsPath=C:/work/karpfen-dsl-tools`). Where
   dsl-tools lives inside the runtime repository, point at its initialized submodule, for example
   `dslToolsPath=../karpfen-runtime/karpfen-dsl-tools`. Note that the dsl-tools build regenerates its ANTLR sources
   into its own `src/main/java`.

2. **Artifact**, when no checkout is found or `-PdslToolsPath=` is given an empty value. The version from the version
   catalog is resolved from the repositories, which until dsl-tools is published means your local Maven repository; the
   build log shows `karpfen-dsl-tools: resolving io.karpfen:karpfen-dsl-tools:<version> from repositories`. dsl-tools
   does not publish itself yet; to put it into `~/.m2` once, run this from the dsl-tools checkout:

   ```bash
   cat > /tmp/publish-dsl-tools.gradle <<'EOF'
   allprojects {
       apply plugin: 'maven-publish'
       afterEvaluate {
           publishing {
               publications {
                   dslTools(MavenPublication) {
                       from components.java
                       groupId = 'io.karpfen'
                       artifactId = 'karpfen-dsl-tools'
                       version = '1.0-SNAPSHOT'
                   }
               }
           }
       }
   }
   EOF
   ./gradlew --init-script /tmp/publish-dsl-tools.gradle publishToMavenLocal
   ```

The configuration cache is enabled in `gradle.properties`, so a build that reuses a cache entry does not print the mode
line again. To see the active mode, run with `--no-configuration-cache` or ask
`./gradlew :core:core-document:dependencyInsight --dependency karpfen-dsl-tools --configuration compileClasspath`. The
dsl-tools build is not compatible with the configuration cache yet: in composite mode, builds that compile the IDE store
no cache entry, and dsl-tools tasks invoked directly (for example `./gradlew :karpfen-dsl-tools:jar`) need
`--no-configuration-cache`.

CI (`.github/workflows/build.yml`) builds on Linux and macOS in composite mode, with dsl-tools pinned to the commit in
`DSL_TOOLS_REF` until dsl-tools publishes artifacts. The workflow file explains the pin and its choice of Gradle cache.

## Modules

Every module keeps its code below the package prefix `io.karpfen.ide`, followed by its layer and name; the
[codebase guide](docs/codebase-guide.md) walks through them feature by feature.

**Core**, free of any UI:

- **core/core-text**: the text of one document, with atomic edits, immutable snapshots, line and column positions,
  undo and redo with typing coalescing, and the mapping of positions between versions. Start at `EditableText` and
  `TextSnapshot`.
- **core/core-document**: tokens, parse outcomes and diagnostics of a document on top of karpfen-dsl-tools, kept up to
  date by a document session. Start at `DocumentSession` (package `session`) and `LanguageSupport` (package
  `language`).
- **core/core-workspace**: an opened folder, with its file tree and watcher, open documents and saving, environment
  descriptors, modeling contexts, the dsl-tools analysis of every context, and the problems of every file. Start at
  `Workspace`.
- **core/core-lang**: the language services for highlighting, completion, go to definition, references, hover and
  bracket matching in the three languages. Start at `LanguageServices` and `LanguageFeatures` (package `feature`).

**UI**, built with Compose Multiplatform for Desktop:

- **ui/ui-foundation**: theme, icons and common widgets (tree view, tabs, split panes, tooltip, status bar), the
  command registry and the toolkit-neutral key model. Start at `CommandRegistry` (package `command`) and `KeyStroke`
  (package `keys`).
- **ui/ui-editor**: the custom code editor, made of a Compose-free model of actions, carets, completion and find, a
  state holder, the Compose view and its popups. Start at `CodeEditorState` (package `state`) and `CodeEditor`
  (package `view`).
- **ui/ui-workbench**: the window content, with project tree, editor tabs, outline, problems, status bar, dialogs,
  settings and file templates, coordinated by one mediator. Start at `WorkbenchState` (package `state`) and the
  `Workbench` composable.

**Application and tools:**

- **app/ide**: the desktop application, with entry point, composition root, menus, crash handling, logging and
  packaging. Start at `IdeApplication`.
- **tools/cli**: the `karpfen` command-line tool, whose `validate` command checks a folder like the IDE does. Start at
  `KarpfenCli` and `ValidateCommand` (package `validate`).

**Build and architecture:**

- **build-logic**: the convention plugins `karpfen.kotlin-jvm`, `karpfen.kotlin-serialization`, `karpfen.compose` and
  `karpfen.application`, and the module dependency matrix `ModuleDependencyRules`, which `verifyModuleDependencies`
  checks as part of every module's `check`. All versions live in `gradle/libs.versions.toml`.
- **architecture-tests**: Konsist rules that keep the layers, the adapters of foreign APIs and the UI state holders
  apart, for example `LayerImportRulesTest` and `ComposeFreeStateTest`, and the documentation style rules of
  `DocumentationStyleTest`.

Together they enforce the module boundaries on every build, as the
[decision that module boundaries are enforced by the build](docs/adr/0008-module-boundaries-enforced-by-the-build.md)
records. The same build enforces the documentation style: `DocumentationStyleTest` rejects line comments, block
comments other than KDoc, and reference ids to other documents in every Kotlin source file and Gradle script. The KDoc
of `DocumentationStyleTest`, `KotlinComments` and `ReferenceIds` explains the rules.

**Later increments**, stubs so far:

- **runtime/runtime-protocol**, **runtime/runtime-session** and **runtime/runtime-host**: the typed client of the
  karpfen runtime, deployment with a live mirror of the model, and hosting the runtime, by default in an IDE-owned
  Docker container.
- **notation/notation-diagram** and **notation/notation-sync**: the UI-agnostic diagram model with its layout, and the
  synchronization of text, model and diagram.
- **bridges/bridge-api**, **bridges/bridge-mapping**, **bridges/bridge-mqtt**, **bridges/bridge-ros** and
  **bridges/bridge-runner**: bridges to MQTT and ROS with declarative message mapping, and their headless runner.
- **ui/ui-diagram**: the diagram canvas and its interaction.

## License

Apache License 2.0, see [LICENSE](LICENSE).
