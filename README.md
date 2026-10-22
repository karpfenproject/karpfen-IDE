# karpfen IDE

karpfen IDE is a desktop IDE for the karpfen DSL ecosystem: KMeta metamodels (`.kmeta`), KModel instances
(`.kmodel`) and KStates hierarchical statecharts (`.kstates`). It aims at textual editing with full language
support, blended textual/graphical notations, deploying to and experimenting with a local karpfen runtime,
bridges to MQTT/ROS, and live visualization of state machines and models. It is written in Kotlin on the JVM with
Compose Multiplatform for Desktop and builds on [karpfen-dsl-tools](https://github.com/karpfenproject/karpfen-dsl-tools)
as its language core.

The accepted architecture is described in [docs/architecture-proposal.md](docs/architecture-proposal.md); the
individual decisions are recorded as ADRs in [docs/adr/](docs/adr/README.md).

Status: **phase 1 (text editing) complete**. The IDE opens a folder of karpfen files and edits them with syntax and
semantic highlighting (including the Python of `EVAL` bodies), live syntax diagnostics, the diagnostics of a
karpfen-dsl-tools analysis of each modeling context, completion, go to definition, hover, outline, problems, find and
replace, and a project tree with file templates; `karpfen validate` checks a folder from the command line. The phase's
contract is [docs/p1-text-editing-spec.md](docs/p1-text-editing-spec.md) (work packages in
[docs/p1-work-packages.md](docs/p1-work-packages.md), manual smoke test in
[docs/p1-manual-test.md](docs/p1-manual-test.md)). Diagrams, the runtime and the bridges (later phases) are stubs.

## Modules

| Module | Purpose | Status |
|---|---|---|
| `core/core-text` | Text buffer, `TextEdit`, offset/line-column mapping, line index, undo/redo with typing coalescing | P1 |
| `core/core-document` | Parse pipeline on top of karpfen-dsl-tools (tokenizers, ANTLR adapter, outline, symbols), document sessions, diagnostics, text patcher | P1 (no patcher yet) |
| `core/core-workspace` | Project model (scan, watch, open documents, save), environment descriptor, modeling contexts, project analysis, problems, element identities, snapshot diff | P1 (no identities/diff yet) |
| `core/core-lang` | Language services: highlighting, completion, hover, go-to, references, bracket matching, rename, folding | P1 (no rename or folding yet) |
| `runtime/runtime-protocol` | Typed HTTP/WebSocket client for the karpfen runtime, environment lifecycle | stub |
| `runtime/runtime-session` | Deploy pipeline, live model mirror, event injector, trace stream | stub |
| `runtime/runtime-host` | `RuntimeHost` with the IDE-owned Docker runtime (default), attach and process hosts | stub |
| `notation/notation-diagram` | UI-agnostic diagram model, layout sidecar, ELK auto-layout adapter | stub |
| `notation/notation-sync` | Blended synchronizer text ⇄ model ⇄ diagram, runtime overlay | stub |
| `bridges/bridge-api` | Bridge lifecycle, sources/sinks, codecs, mapping SPI | stub |
| `bridges/bridge-mapping` | Declarative mapping engine | stub |
| `bridges/bridge-mqtt` | MQTT bridge (HiveMQ MQTT client) | stub |
| `bridges/bridge-ros` | ROS bridge over the rosbridge protocol (JSON over WebSocket) | stub |
| `bridges/bridge-runner` | Headless bridge runner (CLI) | stub |
| `ui/ui-foundation` | Theme, dock/split layout, command registry, keymap, common widgets | P1 |
| `ui/ui-editor` | Custom code editor component (Compose-free model, Compose view, completion/find/hover popups) | P1 |
| `ui/ui-diagram` | Diagram canvas and interaction | stub |
| `ui/ui-workbench` | Project tree, editor tabs, problems, outline, status bar, settings, runtime/bridge tool windows, log console | P1 (no runtime/bridge windows yet) |
| `app/ide` | Desktop application entry point, wiring, menus, crash handling, logging, packaging | P1 |
| `tools/cli` | `validate` / `format` / `deploy` / `export-layout` for CI | P1 (`validate`) |
| `architecture-tests` | Konsist rules enforcing the module boundaries, and the documentation style rules | active |

`build-logic/` holds the convention plugins (`karpfen.kotlin-jvm`, `karpfen.kotlin-serialization`,
`karpfen.compose`, `karpfen.application`); all versions live in `gradle/libs.versions.toml`.

Module boundaries are enforced twice during `./gradlew build`:

- `verifyModuleDependencies` (every module, part of `check`) rejects project dependencies that violate the
  dependency matrix in `build-logic/src/main/kotlin/io/karpfen/ide/buildlogic/ModuleDependencyRules.kt`. It checks
  the declarations of every configuration and the project dependencies the classpaths actually resolve to.
- `architecture-tests` (Konsist) rejects forbidden imports and fully qualified references, e.g. Compose, Ktor or
  Docker in `core/*`, or AWT/Swing outside `ui/*` and `app/`. All modules are Kotlin-only: Konsist reads Kotlin
  files, so Java sources under `src/` are rejected as well.

The same build enforces the documentation style: `DocumentationStyleTest` in `architecture-tests` rejects line
comments, block comments other than KDoc, and reference ids to other documents in every Kotlin source file and Gradle
script. The KDoc of `DocumentationStyleTest`, `KotlinComments` and `ReferenceIds` explains the rules.

## karpfen-dsl-tools resolution

The IDE depends on `io.karpfen:karpfen-dsl-tools`. The build resolves it in one of two modes
([ADR 0010](docs/adr/0010-dsl-tools-resolution-modes.md)):

1. **Composite build** (for working on both repositories at once). The path to a dsl-tools checkout is taken from
   - the Gradle property `-PdslToolsPath=<path>`, else
   - `dslToolsPath=<path>` in a git-ignored `local.properties` in the repository root, else
   - the default `../karpfen-dsl-tools` (a sibling checkout).

   `local.properties` is read as a Java properties file, where `\` is an escape character: on Windows write
   `dslToolsPath=C:/work/karpfen-dsl-tools` (or double every backslash).

   If that directory contains a Gradle build, it is included and substitutes the artifact. The build log shows
   `karpfen-dsl-tools: composite build from <path>`. On a machine where dsl-tools lives inside the runtime
   repository, point at the initialized submodule, e.g. `dslToolsPath=../karpfen-runtime/karpfen-dsl-tools`.
   Note that the dsl-tools build regenerates its ANTLR sources into its own `src/main/java`.

2. **Artifact** (no checkout found, or `-PdslToolsPath=` with an empty value). The version from the catalog is
   resolved from the repositories; until dsl-tools is published, that means your local Maven repository. The build
   log shows `karpfen-dsl-tools: resolving io.karpfen:karpfen-dsl-tools:<version> from repositories`.
   dsl-tools does not apply `maven-publish` yet; to publish it to `~/.m2` once, run this from the dsl-tools checkout:

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

The mode line is printed while the settings are evaluated, so a build that reuses the configuration cache does not
repeat it. To see the active mode, run with `--no-configuration-cache` or use
`./gradlew :core:core-document:dependencyInsight --dependency karpfen-dsl-tools --configuration compileClasspath`.

The configuration cache is enabled (`gradle.properties`). In composite mode, the dsl-tools build script is not
configuration-cache compatible yet, so builds that compile the IDE run without storing a cache entry (Gradle reports
"Configuration cache entry discarded because incompatible task was found"); artifact-mode builds use the cache
normally. Invoking dsl-tools tasks directly in composite mode (e.g. `./gradlew :karpfen-dsl-tools:jar`) fails under
the configuration cache; add `--no-configuration-cache` for those until dsl-tools stops rewriting its generated
sources (architecture proposal §5.1, item 6).

CI clones dsl-tools at a pinned commit (`DSL_TOOLS_REF` in `.github/workflows/build.yml`) and builds in composite
mode until dsl-tools publishes artifacts (ADR 0010). CI caches the Gradle User Home with the MIT-licensed basic
provider of `gradle/actions/setup-gradle` (`cache-provider: basic`); its default, enhanced caching, is a proprietary
component under the Gradle Technologies Terms of Use and has not been chosen.

## Build and run

Requirements:

- A JDK 17 or newer to run Gradle (JDK 21 recommended). Everything else uses the JDK 21 toolchain, which is taken from
  the local installations or provisioned automatically through the foojay resolver: compiling, tests, the `run` tasks
  and packaging. For `app/ide`, the Compose `javaHome` is pinned to that toolchain, so `:app:ide:run`, jlink/jpackage
  and the runtime bundled into the app image are JDK 21 even when Gradle runs on an older JDK.
- Network access to the Gradle Plugin Portal, Maven Central and Google Maven; for automatic JDK provisioning also to
  the foojay discovery API (`api.foojay.io`) and the JDK vendor's download host.

```bash
./gradlew build                      # compile, unit tests, architecture tests, module dependency checks
                                     # (time-budget tests, tag `budget`, are never run: docs/adr/0011)
./gradlew :app:ide:run               # start the IDE (empty state: Open Folder… or a recent folder)
./gradlew :app:ide:run --args=/path/to/folder   # start the IDE on a folder of karpfen files
./gradlew :tools:cli:run             # command-line tool (prints usage)
./gradlew :bridges:bridge-runner:run # headless bridge runner (prints usage)
./gradlew :tools:cli:installDist     # start script: tools/cli/build/install/karpfen/bin/karpfen
./gradlew :bridges:bridge-runner:installDist  # .../install/karpfen-bridge-runner/bin/karpfen-bridge-runner
./gradlew :app:ide:packageDistributionForCurrentOS   # native installer (dmg / msi / deb)
```

The installer version is `0.1.0` for Msi and Deb. jpackage on macOS rejects an app version whose first number is 0,
so the macOS app image and dmg are versioned `1.0.0` (override in `app/ide/build.gradle.kts`) until the product reaches
1.x. The dmg is written to `app/ide/build/compose/binaries/main/dmg/`.

### Running the IDE

`./gradlew :app:ide:run --args=<folder>` opens the folder (try a copy of the examples:
`cp -R core/core-document/src/testFixtures/resources/examples /tmp/karpfen-examples`). Without a folder the IDE shows
an empty state with "Open Folder…" and the recent folders. Every directory with karpfen files forms a modeling
context (its `.kmeta` metamodel, `EVENTS.kmeta`, its `.kmodel` models and `.kstates` state machines) unless an
environment descriptor (`*.kenv.json`) names the files of a context explicitly. Text files on disk are the single
source of truth; changes made by other tools are picked up (on macOS the JDK's file watcher polls, so this takes up to
about 10 seconds).

Settings (`settings.json`: recent folders, theme, font size, window bounds, tool-window sizes) and the log
(`logs/ide.log`) live in `~/.karpfen-ide`; the environment variable `KARPFEN_IDE_HOME=<dir>` moves both, e.g. for a
throw-away run. Launch smoke test: with `KARPFEN_IDE_EXIT_AFTER_MS=<ms>` set, the IDE closes itself after that many
milliseconds and exits with status 0, e.g.

```bash
KARPFEN_IDE_HOME=/tmp/karpfen-home KARPFEN_IDE_EXIT_AFTER_MS=6000 ./gradlew :app:ide:run --args=/tmp/karpfen-examples
grep ERROR /tmp/karpfen-home/logs/ide.log   # prints nothing
```

Before a release, run the 10-minute manual smoke test in [docs/p1-manual-test.md](docs/p1-manual-test.md) (German
keyboard layout, completion, navigation, save/undo, external changes, quit).

### Command-line tool

`karpfen validate` checks a folder like the IDE does (syntax of every file plus the dsl-tools analysis of every
modeling context and the environment descriptors) and prints one line per problem, with one-based positions relative
to the folder:

```bash
./gradlew :tools:cli:installDist
tools/cli/build/install/karpfen/bin/karpfen validate <folder> [--format text|json]
# karpfen-runtime/single_robot_example/cleaning_robot.kstates:8:13: error: extraneous input 'ENTRY' expecting '{' [syntax-error]
```

Exit status: 0 without errors (warnings and infos are printed but allowed), 1 if any error was found, 2 for an invalid
command line or a missing folder, 3 if the validation itself failed (a defect; see the message). `--format json`
prints `{root, errorCount, warningCount, problems: [{path, line, column, severity, message, code}]}`. The output
contains only the report: what dsl-tools prints while it analyzes is suppressed.

## License

Apache License 2.0, see [LICENSE](LICENSE).
