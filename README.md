# karpfen IDE

karpfen IDE is a desktop IDE for the karpfen DSL ecosystem: KMeta metamodels (`.kmeta`), KModel instances
(`.kmodel`) and KStates hierarchical statecharts (`.kstates`) with embedded Python in their `EVAL` blocks;
environment descriptors (`*.kenv.json`) tie them together. It aims at textual editing with full language support,
blended textual and graphical notations, deploying to and experimenting with a local karpfen runtime, bridges to MQTT
and ROS, and live visualization of state machines and models. It is written in Kotlin on the JVM with Compose
Multiplatform for Desktop and builds on [karpfen-dsl-tools](https://github.com/karpfenproject/karpfen-dsl-tools) as its
language core.

**Status: the text editor (phase 1) and the first three increments after it are complete.** The IDE opens a folder of
karpfen files and edits them with syntax and semantic highlighting (including the Python of `EVAL` bodies), live syntax
diagnostics, the diagnostics of a karpfen-dsl-tools analysis of each modeling context, completion, go to definition,
hover, outline, problems, find and replace, and a project tree with file templates; environment descriptors are edited
the same way, with completion of their keys, files and objects. Completion opens with Ctrl+M on every platform, and on
Windows also with Ctrl+Space. `karpfen validate` checks a folder from the command line. karpfen-dsl-tools 2.0 reads
text with diagnostics at their source positions instead of throwing, prints models back as text and logs through SLF4J;
the IDE places its problems precisely, and what it logs stays off both consoles and is shown on demand: in the IDE's
Language Tools Output and with `karpfen validate --verbose`. The third increment built the runtime client and the
runtime's IDE support: the karpfen runtime gained a model snapshot, revisions, a subscription to the whole model and
domain events on its branch with IDE support, and the IDE deploys an environment descriptor to it, observes the model
behind a live switch and injects events. The client is tested against a fake runtime that answers only what each test
programs and, on demand, against a real runtime in throwaway Docker containers. It has no user interface yet; the work
continues with the next of the [increments after phase 1](docs/plans/increments-after-phase-1.md). Diagrams, hosting the
runtime from the IDE and the bridges are stubs so far.

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
- The git submodules: clone with `git clone --recurse-submodules`, or run `git submodule update --init` in an existing
  clone. karpfen-dsl-tools is a submodule; see [karpfen-dsl-tools resolution](#karpfen-dsl-tools-resolution) below.
  karpfen-runtime is a submodule too: the tests of the runtime client compare it with the runtime's sources, and the
  live tests build the runtime from it. The IDE needs the runtime's branch with IDE support, feature/ide-support, to
  which the submodule points.

### Gradle tasks

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles every module and runs the unit tests, the architecture tests and the module dependency check. |
| `./gradlew :core:core-lang:test` | Runs the tests of one module, the quick loop while working on it. |
| `./gradlew :app:ide:run` | Starts the IDE with an empty state that offers Open Folder… and the recent folders. |
| `./gradlew :app:ide:run --args=<folder>` | Starts the IDE on a folder of karpfen files. |
| `./gradlew :app:ide:runWithFailingReaders --args=<folder>` | Starts the IDE with dsl-tools readers that fail on deeply nested state machines, which the manual smoke test uses to fill the Language Tools Output. |
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

Tests against a real karpfen runtime carry the JUnit tag `live-runtime` and run only in the `liveRuntimeTest` task of
the two runtime modules, which no build runs by itself; [checking the client against a real
runtime](#checking-the-client-against-a-real-runtime) below says how to run them.

Before a release, run the [manual smoke test](docs/manual-test.md) at a real machine with a German keyboard layout. It
covers what headless tests cannot: the native menu bar, typing on a real keyboard layout, focus and mouse handling,
changes on disk and quitting.

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

What karpfen-dsl-tools logs never appears on the IDE's console. Its readers, with which the IDE analyzes the files,
report every problem as a diagnostic; they log only a read that fails, which is a problem too, and the contract of
`DslReader` says how. What dsl-tools logs goes to View ▸ Language Tools Output, a tool window below Problems that shows it line by line with
level, logger, the file dsl-tools read and message; it follows new output and can be cleared. The same lines go to `logs/dsl-tools.log` next to the IDE log, and the warnings and errors among
them are forwarded to `logs/ide.log` as well. When warnings or errors arrive while the tool window is hidden, the status
bar shows `Language tools: n`, which opens it. `app/ide/src/main/resources/logback.xml` describes the routing.

For a launch smoke test, `KARPFEN_IDE_EXIT_AFTER_MS=<ms>` makes the IDE close itself after that many milliseconds and
exit with status 0. After this run, the log contains no `ERROR` line:

```bash
KARPFEN_IDE_HOME=/tmp/karpfen-home KARPFEN_IDE_EXIT_AFTER_MS=6000 ./gradlew :app:ide:run --args=/tmp/karpfen-examples
grep ERROR /tmp/karpfen-home/logs/ide.log
```

### Command-line tool

`karpfen validate` checks a folder like the IDE does: the syntax of every file, the dsl-tools analysis of every
modeling context and the environment descriptors. It prints one line per problem, with one-based positions and paths
relative to the folder, and only the report, on standard output; its own messages and warnings go to standard error.
What dsl-tools logs while it analyzes is off, since every problem it finds is in the report anyway; `--verbose` shows
it on standard error, as the
[decision that dsl-tools output stays off the primary console](docs/adr/0014-dsl-tools-output-stays-off-the-primary-console.md)
asks.

```bash
./gradlew :tools:cli:installDist
tools/cli/build/install/karpfen/bin/karpfen validate <folder> [--format text|json] [--verbose]
```

A problem looks like this:

```text
karpfen-runtime/single_robot_example/cleaning_robot.kstates:8:13: error: extraneous input 'ENTRY' expecting '{' [syntax-error]
```

The exit status tells whether errors were found; `ExitCodes` documents the statuses and `ReportFormatter` the text and
JSON formats.

### Runtime client

runtime/runtime-protocol is the typed client of the karpfen runtime with IDE support, and runtime/runtime-session deploys
an environment descriptor from a workspace and keeps a session with the deployed environment: the deployment's
progress, the commands of its lifecycle, the observed model with its live switch, the domain events and the injection
of events. A deployment first saves every open file, because it uploads the saved files, and checks exactly the text it
uploads; the descriptor's `tickDelayMs` and `eventTtlMs`, 50 and 3000 when absent, are sent with every deployment. The
IDE has no user interface for it yet, and the command-line tool has no deploy command. The
[codebase guide](docs/codebase-guide.md#runtime-client-runtime-protocol-runtime-session) maps the code, and the
decisions on [the client](docs/adr/0016-the-runtime-client-talks-to-the-runtime-with-ide-support.md),
[deploying](docs/adr/0017-deployment-uploads-the-saved-files.md) and
[testing it](docs/adr/0018-runtime-work-is-tested-against-an-anchored-fake-and-a-throwaway-runtime-container.md) explain
why it looks the way it does.

### Checking the client against a real runtime

`runtime/live-runtime.sh` builds the runtime from the karpfen-runtime submodule as a Docker image and runs it in
throwaway containers for the live tests. It needs a local Docker engine (a remote engine is refused, since it would
publish the runtime, which has no authentication, on another machine), git, curl, and the runtime submodule with its
own karpfen-dsl-tools submodule initialized (`git -C karpfen-runtime submodule update --init`). `KARPFEN_RUNTIME_DIR`
points it at another checkout of the runtime.

| Command | What it does |
|---|---|
| `runtime/live-runtime.sh test` | Builds the image, starts a runtime, runs the live tests of both runtime modules against it and removes it. |
| `runtime/live-runtime.sh test :runtime:runtime-session:liveRuntimeTest --tests '*Observation*'` | The same with the given Gradle arguments in place of the two live tasks. |
| `runtime/live-runtime.sh up` | Starts one runtime to try things against and prints its URL and how to remove it. |
| `runtime/live-runtime.sh down [run id]` | Removes one runtime of this checkout, or all of them. |
| `runtime/live-runtime.sh status` | Lists the containers, volumes and images the script made. |
| `runtime/live-runtime.sh cleanup` | Removes every container, volume and image the script ever made and reports what stays. |

`test` starts one container for the run, hands its URL to the `liveRuntimeTest` tasks and removes the container when the
run ends, also when it fails or is interrupted; the runtime's log and engine traces stay in `build/live-runtime/runs/`.
The first build downloads the base images and the runtime's dependencies and takes minutes; later builds reuse Docker's
layer cache. The test models need no Python, except the one model that creates objects at run time, and Python runs
only inside the container. Every container, volume and image the script makes carries the label
`io.karpfen.ide.test=true`, and it removes only what carries that label: `down` the containers of this checkout,
`cleanup` every container, volume and image it ever made, after which it reports what stays, the base images and
Docker's build cache, which other projects may share. The `liveRuntimeTest` task also runs against any runtime with IDE
support given as `-Pkarpfen.runtime.url=http://<host>:<port>`.

## karpfen-dsl-tools resolution

The IDE depends on `io.karpfen:karpfen-dsl-tools`. karpfen-dsl-tools is a git submodule of this repository in the
directory `karpfen-dsl-tools`, as the decision that
[required repositories are git submodules](docs/adr/0015-required-repositories-are-git-submodules.md) records, and the
build resolves it in one of two modes, as the [decision on how dsl-tools is resolved](docs/adr/0010-dsl-tools-resolution-modes.md)
describes:

1. **Composite build**, the default: the submodule checkout is included in the build and replaces the artifact, so a
   change that spans both repositories is a single local iteration. Work on dsl-tools happens in the submodule, on a
   branch of the dsl-tools repository. A git-ignored `local.properties` can point `dslToolsPath` at another checkout.
   Without an initialized submodule, the build stops and names the command that checks it out.
2. **Artifact**, chosen with a blank `dslToolsPath`: the version of the version catalog is resolved from GitHub
   Packages, where dsl-tools publishes, with credentials; this works once that version is published. The build never
   reads from or publishes to the local Maven repository.

Either way the IDE needs dsl-tools 2.0 or newer, whose packages lie below `io.karpfen.dsl`; a composite checkout of an
older version stops the build with a message that names it. Both modes keep the configuration cache, since dsl-tools
generates its parsers at build time. The build log names the active mode, except when the configuration cache is
reused; `--no-configuration-cache` shows it then. The KDoc of the resolution block in `settings.gradle.kts` documents
how the path is found. CI checks the submodule out together with this repository, so a submodule commit must be pushed
to the dsl-tools repository before CI can build the commit that points to it.

## Modules

Every module keeps its code below one package prefix, `io.karpfen.ide` followed by its layer and the rest of its name
(`io.karpfen.ide.core.document` for core/core-document); app/ide, architecture-tests and build-logic use the shorter
`io.karpfen.ide.app`, `io.karpfen.ide.architecture` and `io.karpfen.ide.buildlogic`. `KarpfenModules` in the
architecture tests lists the prefix of every module of the product build, where the architecture tests check it;
build-logic, an included build of its own, keeps its prefix by convention. The [codebase guide](docs/codebase-guide.md)
walks through the modules feature by feature.

**Core**, free of any UI:

- **core/core-text**: the text of one document, with atomic edits, immutable snapshots, line and column positions,
  undo and redo with typing coalescing, and the mapping of positions between versions. Start at `EditableText` and
  `TextSnapshot`.
- **core/core-document**: tokens, parse outcomes and diagnostics of a document on top of karpfen-dsl-tools, and of
  environment descriptors on top of the IDE's own JSON parser, kept up to date by a document session. Start at
  `DocumentSession` (package `session`), `LanguageSupport` (package `language`) and, for descriptors,
  `EnvironmentDescriptor` and `EnvironmentSchema` (package `environment`).
- **core/core-workspace**: an opened folder, with its file tree and watcher, open documents and saving, environment
  descriptors, modeling contexts, the dsl-tools analysis of every context, and the problems of every file. Start at
  `Workspace`.
- **core/core-lang**: the language services for highlighting, completion, go to definition, references, hover and
  bracket matching in the three modeling languages and in environment descriptors. Start at `LanguageServices` and
  `LanguageFeatures` (package `feature`).

**UI**, built with Compose Multiplatform for Desktop:

- **ui/ui-foundation**: theme, icons and common widgets (tree view, tabs, split panes, tooltip, status bar), the
  command registry and the toolkit-neutral key model. Start at `TreeView` (package `widgets`), `KarpfenTheme` (package
  `theme`), `CommandRegistry` (package `command`) and `KeyStroke` (package `keys`).
- **ui/ui-editor**: the custom code editor, made of a Compose-free model of actions, carets, completion and find, a
  state holder, the Compose view and its popups. Start at `CodeEditorState` (package `state`) and `CodeEditor`
  (package `view`).
- **ui/ui-workbench**: the window content, with project tree, editor tabs, outline, problems, the output of the
  language tools, status bar, dialogs, settings and file templates, coordinated by one mediator. Start at
  `WorkbenchState` (package `state`) and the `Workbench` composable.

**Application and tools:**

- **app/ide**: the desktop application, with entry point, composition root, menus, crash handling, logging and
  packaging. Start at `IdeApplication`.
- **tools/cli**: the `karpfen` command-line tool, whose `validate` command checks a folder like the IDE does. Start at
  `KarpfenCli` and `ValidateCommand` (package `validate`).

**Runtime**, free of any UI:

- **runtime/runtime-protocol**: the typed client of the karpfen runtime with IDE support: its routes and WebSocket as
  data, the runtime's JSON, the environment lifecycle and the authenticated push channel. It depends on no other module
  of the IDE. Start at `RuntimeClient`.
- **runtime/runtime-session**: deploying an environment descriptor from a workspace and the session with the deployed
  environment: the plan and its checks, the deploy pipeline, the observed model with its live switch, the event catalog
  and the injection of events. Start at `RuntimeSessions` and `RuntimeSession`.

**Build and architecture:**

- **build-logic**: the convention plugins `karpfen.kotlin-jvm`, `karpfen.kotlin-serialization`, `karpfen.compose`,
  `karpfen.application` and `karpfen.live-runtime-test`, and the module dependency matrix `ModuleDependencyRules`,
  which `verifyModuleDependencies` checks as part of every module's `check`. All versions live in
  `gradle/libs.versions.toml`.
- **architecture-tests**: Konsist rules that keep the layers, the adapters of foreign APIs and the UI state holders
  apart, for example `LayerImportRulesTest` and `ComposeFreeStateTest`, and the documentation style rules of
  `DocumentationStyleTest`.

Together they enforce the module boundaries on every build, as the
[decision that module boundaries are enforced by the build](docs/adr/0008-module-boundaries-enforced-by-the-build.md)
records. The same build enforces the documentation style: `DocumentationStyleTest` rejects line comments, block
comments other than KDoc and KDoc that documents no declaration in every Kotlin source file and Gradle script, and
reference ids to other documents there and in the markdown documents, the CI workflows and the XML resources. A Gradle
script has no other place to document its top-level statements and blocks, so KDoc may document those too. The KDoc
of `DocumentationStyleTest`, `KDocPlacement` and `ReferenceIds` explains the rules. Every Kotlin source file and Gradle
script starts with the Apache 2.0 license header that the sources of karpfen-dsl-tools carry too, which
`DocumentationStyleTest` requires and `LicenseHeader` holds.

**Later increments**, stubs so far:

- **runtime/runtime-host**: hosting the runtime, by default in an IDE-owned Docker container.
- **notation/notation-diagram** and **notation/notation-sync**: the UI-agnostic diagram model with its layout, and the
  synchronization of text, model and diagram.
- **bridges/bridge-api**, **bridges/bridge-mapping**, **bridges/bridge-mqtt**, **bridges/bridge-ros** and
  **bridges/bridge-runner**: bridges to MQTT and ROS with declarative message mapping, and their headless runner.
- **ui/ui-diagram**: the diagram canvas and its interaction.

## License

Apache License 2.0, see [LICENSE](LICENSE).
