# karpfen IDE

karpfen IDE is a desktop IDE for the karpfen DSL ecosystem: KMeta metamodels (`.kmeta`), KModel instances
(`.kmodel`) and KStates hierarchical statecharts (`.kstates`). It aims at textual editing with full language
support, blended textual/graphical notations, deploying to and experimenting with a local karpfen runtime,
bridges to MQTT/ROS, and live visualization of state machines and models. It is written in Kotlin on the JVM with
Compose Multiplatform for Desktop and builds on [karpfen-dsl-tools](https://github.com/karpfenproject/karpfen-dsl-tools)
as its language core.

The accepted architecture is described in [docs/architecture-proposal.md](docs/architecture-proposal.md); the
individual decisions are recorded as ADRs in [docs/adr/](docs/adr/README.md).

Status: **phase 0 (skeleton)**. Every module exists and is wired into the build, but contains only a stub.

## Modules

| Module | Purpose | Status |
|---|---|---|
| `core/core-text` | Text buffer, `TextEdit`, offset/line-column mapping, line index | stub |
| `core/core-document` | Parse pipeline on top of karpfen-dsl-tools, document snapshots, diagnostics, text patcher | stub |
| `core/core-workspace` | Project model, environment descriptor, element identities, snapshot diff | stub |
| `core/core-lang` | Language services: completion, hover, rename, folding, outline, go-to | stub |
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
| `ui/ui-foundation` | Theme, dock/split layout, command registry, keymap, common widgets | stub |
| `ui/ui-editor` | Custom code editor component | stub |
| `ui/ui-diagram` | Diagram canvas and interaction | stub |
| `ui/ui-workbench` | Project tree, problems, outline, runtime/bridge tool windows, log console | stub |
| `app/ide` | Desktop application entry point, wiring, packaging | stub (empty window) |
| `tools/cli` | `validate` / `format` / `deploy` / `export-layout` for CI | stub |
| `architecture-tests` | Konsist rules enforcing the module boundaries | active |

`build-logic/` holds the convention plugins (`karpfen.kotlin-jvm`, `karpfen.kotlin-serialization`,
`karpfen.compose`, `karpfen.application`); all versions live in `gradle/libs.versions.toml`.

Module boundaries are enforced twice during `./gradlew build`:

- `verifyModuleDependencies` (every module, part of `check`) rejects project dependencies that violate the
  dependency matrix in `build-logic/src/main/kotlin/io/karpfen/ide/buildlogic/ModuleDependencyRules.kt`. It checks
  the declarations of every configuration and the project dependencies the classpaths actually resolve to.
- `architecture-tests` (Konsist) rejects forbidden imports and fully qualified references, e.g. Compose, Ktor or
  Docker in `core/*`, or AWT/Swing outside `ui/*` and `app/`. All modules are Kotlin-only: Konsist reads Kotlin
  files, so Java sources under `src/` are rejected as well.

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
./gradlew :app:ide:run               # start the IDE (an empty window for now)
./gradlew :tools:cli:run             # command-line tool (prints usage)
./gradlew :bridges:bridge-runner:run # headless bridge runner (prints usage)
./gradlew :tools:cli:installDist     # start script: tools/cli/build/install/karpfen/bin/karpfen
./gradlew :bridges:bridge-runner:installDist  # .../install/karpfen-bridge-runner/bin/karpfen-bridge-runner
./gradlew :app:ide:packageDistributionForCurrentOS   # native installer (dmg / msi / deb)
```

The installer version is `0.1.0` for Msi and Deb. jpackage on macOS rejects an app version whose first number is 0,
so the macOS app image and dmg are versioned `1.0.0` (override in `app/ide/build.gradle.kts`) until the product reaches
1.x. The dmg is written to `app/ide/build/compose/binaries/main/dmg/`.

Launch smoke test: with `KARPFEN_IDE_EXIT_AFTER_MS=<ms>` set, the IDE closes itself after that many milliseconds
and exits with status 0, e.g. `KARPFEN_IDE_EXIT_AFTER_MS=4000 ./gradlew :app:ide:run`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
