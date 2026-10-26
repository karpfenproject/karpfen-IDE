# karpfen IDE — Architecture & Technology Proposal

> This is the accepted design record of karpfen IDE, kept as it was accepted. Each of its key decisions is also
> recorded on its own in the [architecture decision records](adr/README.md). It is not a map of the current code: to
> find your way through the code, read the [codebase guide](codebase-guide.md).

Status: v0.3, 2026-10-20 — **accepted by the owner as the working architecture; no implementation started yet.**
Generated with Claude from a review of `karpfenproject/karpfen-runtime` and `karpfenproject/karpfen-dsl-tools` plus library research.

Decision log:

| Date | Decision | By |
|---|---|---|
| 2026-10-20 | `karpfen-dsl-tools` becomes a dependency of the IDE, at least for verification; future semantic analysis goes into the dsl-tools repo (see [dsl-tools is the language core](#karpfen-dsl-tools-is-the-language-core-the-ide-adds-only-editor-concerns) and [what dsl-tools must provide](#language-core-what-dsl-tools-must-provide-what-the-ide-adds)) | owner |
| 2026-10-20 | Compose Multiplatform for Desktop is the UI toolkit (see [the UI toolkit decision](#ui-toolkit-compose-multiplatform-for-desktop-jvm-custom-editor-and-custom-diagram-canvas)) | owner |
| 2026-10-20 | The IDE owns a Docker-managed runtime container by default (see [the runtime decision](#the-runtime-runs-in-an-ide-owned-docker-container-by-default-wire-protocol-only) and [runtime integration](#runtime-integration-runtime-modules)) | owner (asked), accepted |
| 2026-10-20 | All remaining recommendations in this document accepted (see the [decisions record](#decisions-record)) | owner |
| 2026-10-26 | Every repository the IDE needs is a git submodule of the IDE repository, and work stays inside the workspace; karpfen-dsl-tools is the first (replaces the sibling checkout path below; see the [decision record on git submodules](adr/0015-required-repositories-are-git-submodules.md)) | owner |

---

## What the existing ecosystem gives us

### Languages

| Language | File | Nature | Grammar size (ANTLR 4.13) |
|---|---|---|---|
| KMeta | `.kmeta` | Metamodel: `type`, `prop`, `has` (composition), `knows` (reference), `list(...)`; **last type = root** | ~15 rules |
| KModel | `.kmodel` | Instance tree: `make object "key":"Type"` with `prop`/`has`/`knows`; `knows` resolved by key after parse | ~10 rules |
| KStates | `.kstates` | Hierarchical statechart attached to a type: `STATES` (nested, `INITIAL`, `ENTRY`/`DO`), `TRANSITIONS` (ordered = priority, `NOT LOOPING`, `SPLIT`/`JOIN`), `MACROS`; `EVAL { python }` opaque blocks; `$(path)` substitution | ~45 rules |
| Event payloads | `EVENTS.kmeta` | Ordinary KMeta whose type names double as event names | — |

Facts that shape the editor design:

- `EVAL` bodies are raw text captured by token interval; the grammar's `//` comment rule also fires *inside* EVAL (documented caveat: Python `//` breaks). The IDE must show what the runtime sees and warn.
- Comments and whitespace are discarded by the reference parsers; the `Parsed*` intermediate classes carry **no source positions** today.
- There is **no unparser** yet: `convertToKmetaString` / `convertToKmodelString` throw `NotImplementedError`; nothing prints KStates.
- State names are **global identifiers**: the engine resolves a transition target by depth-first name search (`StateMachineQueryHelper.findStatePath`), so duplicate names silently bind to the first match.
- `$(path)` roots: the context object (`self`/implicit), `event` (scoped event payload), `WITH ... AS "name"` bindings; `@macro(...)` calls inside Python.

### karpfen-dsl-tools (library)

Kotlin 2.3.0, Gradle 9.0, JDK 21, ANTLR 4.13.1 runtime. Packages live in the root namespace (`meta`, `instance`, `states`, `parser`, `dsl`), generated ANTLR sources are committed under `src/main/java` with a package-rewrite task, converters `println` while parsing, and the library is consumed by the runtime as a **git submodule** (nothing is published to a Maven repository). On this machine it is checked out and initialized at `../karpfen-runtime/karpfen-dsl-tools`.

### karpfen-runtime (server)

Ktor 2.3.7 (Netty), HTTP API with query parameters and `text/plain` bodies, WebSocket at `/ws` with a first-message handshake `clientId:envKey:accessKey`, JSON messages afterwards. Python 3.12+ subprocess (persistent session) executes EVAL/MACRO. Configuration is read **only** from `application.conf` in the working directory; the Docker image (`debian:trixie-slim`, JRE 21, Python 3 with numpy/scipy/sympy/pandas) does not bake it in and expects it at `/app/application.conf`, with trace logs under `/app/logs`.

Environment lifecycle (immutable once activated):

```
createEnvironment → setMetamodel → setModel → [setEventDefinitions] → setStateMachine(attachedTo)* → [setTickDelay, setEventTtl]
  → registerClientForWebSocket / registerObjectObserver / registerDomainListener / observatory.registerClient
  → runEnvironment (activate) → startEnvironment → ... → stopEnvironment
```

Push messages: `objectChanged {objectId, value: <full JSON subtree with __id__/__type__>}`, `objectDeleted`, `domainEvent {domain, payload}`, observatory `stateChange {modelElementId, stack}` (subscribe with `modelElement="*"` for all machines). Inbound: `{environmentKey (= event domain), messageType (= event name = payload type), payload, payloadFormat}`.

### Gaps the IDE will run into (small changes in your own repos)

| Gap | Impact on IDE | Suggested change |
|---|---|---|
| No model snapshot endpoint | Live visualizer must reconstruct state from the uploaded `.kmodel` + change stream; reconnect loses state | `GET /observatory/model?envKey` returning the full model JSON |
| Object observers are per `objectId`, registered up front | Objects created at runtime (`APPENDOBJ`) are invisible | wildcard `objectId="*"` like the observatory already has |
| No `DELETE /environment`, no list of inactive envs | Long IDE sessions leak environments | `DELETE /environment?envKey`, `GET /environments` |
| Observatory pushes only `stateChange` | Cannot animate the fired transition / event consumption | optional `transitionFired` / `eventConsumed` push (opt-in flag at registration) |
| Parse errors surface as exceptions without positions | IDE cannot map server-side errors to text | 400 with `{line, column, message}` once dsl-tools has source ranges (see [what dsl-tools must provide](#language-core-what-dsl-tools-must-provide-what-the-ide-adds)) |
| Configuration only via `application.conf` file | Managed container needs a config injected before start | env-var / system-property overrides (`KARPFEN_SERVER_PORT`, …) or CLI flags |
| No version endpoint, no published image | IDE cannot check compatibility; must build the image from source | `GET /version`; publish `ghcr.io/karpfenproject/karpfen-runtime` (multi-arch) from CI |
| dsl-tools not published, root packages | Hard to depend on, name clashes | publish `io.karpfen:*` artifacts; move to `io.karpfen.dsl.*` |

---

## Goals and non-goals

Goals (from the brief): textual editing with full language support; round-trip to a graphical/textual hybrid notation (blended modeling); deploy and experiment against a local runtime; bridges to MQTT/ROS and similar; live visualization of state and data models. Custom implementation preferred; well-maintained libraries acceptable.

Non-goals for the first year: multi-user collaboration, browser deployment, a generic metamodel-agnostic graphical editor framework. The layering below keeps those options open but does not pay for them now.

---

## Key architectural decisions

### Text files are the single source of truth; diagrams are projections with a layout sidecar

Rationale: the runtime consumes text, git diffs stay meaningful, textual editing is a first-class requirement, and we avoid inventing a model persistence format. Consequence: graphical edits are compiled into minimal text edits so comments, formatting and Python bodies survive. Layout lives in `<file>.layout.json` next to the source, keyed by stable element identities (see [the notation layer](#notation-layer-notation-diagram-ui-diagram)). Recorded in [the decision record on text files as the single source of truth](adr/0002-text-files-are-the-single-source-of-truth.md).

### karpfen-dsl-tools is the language core; the IDE adds only editor concerns

Decided by the owner. The IDE depends on dsl-tools for lexing, parsing, the semantic model and, as it grows there, semantic analysis. There is deliberately **no second parser** in the IDE: two implementations of an evolving, explicitly experimental grammar would drift, and the runtime and the editor must agree by construction. For dsl-tools to serve as an editor backend it needs a small set of additions listed under [what dsl-tools must provide](#language-core-what-dsl-tools-must-provide-what-the-ide-adds) (source ranges, diagnostics instead of exceptions, error-tolerant partial results, a tokenizer entry point, printers, a scope/query API). Until those land, the IDE can already use the ANTLR lexer for highlighting and the strict parser for file-level diagnostics. Locally the dependency is a Gradle composite build; CI uses published artifacts. Recorded in [the decision record on the language core](adr/0003-dsl-tools-is-the-language-core.md).

### Hybrid notation: graph structure is graphical, leaves are textual

KStates: states, nesting, transitions, SPLIT/JOIN bars, INITIAL markers are drawn and manipulated graphically; `ENTRY`/`DO` blocks, conditions and macro bodies are embedded text cells using the same editor component as the text view (syntax-highlighted, validated). KMeta: a class-diagram view (types, props, composition vs reference edges, multiplicities). KModel: an object diagram / containment tree with values, which doubles as the live visualizer surface. Recorded in [the decision record on the hybrid notation](adr/0004-hybrid-notation.md).

### UI toolkit: Compose Multiplatform for Desktop (JVM), custom editor and custom diagram canvas

Decided by the owner. The trade-off table is kept under [technology choices](#technology-choices) for the record. The decision is confined to `ui/*` and `app/`; every other module is UI-agnostic by construction and enforced by architecture tests. Recorded in [the decision record on the UI toolkit](adr/0005-compose-multiplatform-for-desktop.md).

### The runtime runs in an IDE-owned Docker container by default; wire protocol only

The IDE talks to the runtime exactly like any other client over HTTP/WebSocket, so IDE and runtime stay independently versioned. The container's whole lifecycle (image pull, config injection, start, health, logs, stop, cleanup) is owned by the IDE, so users never touch Docker themselves (see [runtime integration](#runtime-integration-runtime-modules)). Alternatives behind the same interface: attach to an external runtime URL, or run the distribution as a host process where Docker is unavailable. In-process embedding of `execution-engine` (tick stepping, breakpoints) is a later, optional module. Recorded in [the decision record on the IDE-owned runtime container](adr/0006-runtime-in-ide-owned-docker-container.md).

### Bridges are headless-first

A bridge is a plain Kotlin component with a lifecycle, runnable from a CLI on a robot or edge box without the IDE; the IDE configures, hosts and monitors bridges during development. Message mapping is declarative first, with a Kotlin SPI for custom codecs/transforms. Recorded in [the decision record on headless-first bridges](adr/0007-headless-first-bridges.md).

### Module boundaries are enforced by the build

Gradle convention plugins in `build-logic/`, a version catalog, `api`/`implementation` discipline, and architecture tests (Konsist) that fail the build if `core/*` or `notation/*` import Compose, Ktor, Docker or any UI package. Recorded in [the decision record on module boundaries](adr/0008-module-boundaries-enforced-by-the-build.md).

### Reactive backbone: kotlinx.coroutines with Flow and StateFlow

Each open document is a structured-concurrency scope; each runtime session likewise. No DI framework initially; explicit wiring in `app/ide`. Recorded in [the decision record on the reactive backbone](adr/0009-coroutines-flow-reactive-backbone.md).

---

## Module map

```
karpfen-ide/
├── settings.gradle.kts                # includeBuild("build-logic"); includeBuild(dslToolsPath) with dependency substitution (see the language core below)
├── gradle/libs.versions.toml
├── build-logic/                       # convention plugins: karpfen.kotlin-jvm, karpfen.compose, karpfen.testing, karpfen.application
│
├── core/                              # depends on karpfen-dsl-tools, stdlib, coroutines, serialization — nothing else
│   ├── core-text                      # text buffer (piece table or line array), TextEdit, offsets↔line/col, line index
│   ├── core-document                  # parse pipeline on top of dsl-tools: debounce/cancel scheduler, DocumentSnapshot (text, tokens,
│   │                                  # parse result, source map, version), diagnostics mapping, text patcher (semantic command → TextEdit[])
│   ├── core-workspace                 # project model, environment descriptor, element identities, snapshot diff
│   └── core-lang                      # language services over dsl-tools' analysis/scope API: completion, hover, rename, folding,
│                                      # semantic tokens, outline, go-to
│
├── runtime/
│   ├── runtime-protocol               # typed client for every endpoint + WS session (Ktor client 3, kotlinx.serialization), env lifecycle FSM
│   ├── runtime-session                # deploy pipeline from environment descriptor, live model mirror, event injector, trace/log stream
│   ├── runtime-host                   # RuntimeHost interface + DockerRuntimeHost (default), AttachRuntimeHost, ProcessRuntimeHost;
│   │                                  # image management, config injection, health, log streaming, reconciliation
│   └── runtime-embedded (later)       # in-process engine via composite build; tick stepping, breakpoints on transitions
│
├── notation/
│   ├── notation-diagram               # UI-agnostic diagram model: nodes/compartments/text cells/edges/pseudo-nodes, geometry, hit testing,
│   │                                  # commands, layout store (sidecar JSON), auto-layout adapter (ELK)
│   └── notation-sync                  # blended synchronizer: text ⇄ semantic model ⇄ diagram, provenance, undo integration, runtime overlay
│
├── bridges/
│   ├── bridge-api                     # Bridge lifecycle, Endpoint/Source/Sink, Codec, Mapping SPI, metrics
│   ├── bridge-mapping                 # declarative mapping engine (routes, path expressions, templates), payload type derivation
│   ├── bridge-mqtt                    # HiveMQ MQTT client
│   ├── bridge-ros                     # rosbridge (WebSocket/JSON) client; later optional native DDS/Zenoh transport
│   └── bridge-runner                  # headless CLI (application plugin), config file in, logs/metrics out
│
├── ui/                                # Compose Multiplatform (desktop)
│   ├── ui-foundation                  # theme, split/dock layout, command registry, keymap, common widgets
│   ├── ui-editor                      # code editor component (virtualized lines, highlighting, gutter, squiggles, completion popup)
│   ├── ui-diagram                     # canvas renderer + interaction for notation-diagram (pan/zoom, drag, edge creation, embedded editor cells)
│   └── ui-workbench                   # project tree, problems view, outline, runtime/bridge tool windows, log console, event injector forms
│
├── app/
│   └── ide                            # entry point, wiring, packaging (Compose Gradle plugin → dmg/msi/deb), settings
│
└── tools/
    ├── cli                            # validate / format / deploy / export-layout, usable in CI
    └── lsp-server (later)             # LSP4J façade over core-lang for VS Code / Neovim users
```

Allowed dependency directions: `ui/*` → `notation/*`, `runtime/*`, `bridges/*`, `core/*`; `notation/*` → `core/*`; `runtime/*` → `core-workspace`, `core-text`; `bridges/*` → `runtime-protocol`, `core-workspace`; `core/*` → `karpfen-dsl-tools`, stdlib, coroutines, serialization only. `runtime-host` is the only module allowed to import the Docker client. All modules are plain Kotlin/JVM.

---

## Design of the core pieces

### Language core: what dsl-tools must provide, what the IDE adds

Everything language-specific lives in `karpfen-dsl-tools` (your repo); the IDE consumes it. The additions below are ordered by when the IDE needs them. None changes the runtime's behaviour; the runtime keeps a strict wrapper that throws on the first diagnostic.

1. **Source ranges.** ANTLR tokens already carry positions; the converters drop them. Least invasive design: a `SourceMap` side table (`IdentityHashMap<Any, SourceRange>`) returned next to the model, so the model classes stay runtime-neutral and unchanged. Ranges are needed for: type, `prop`/`has`/`knows` rule, object, statement, state, `ENTRY`/`DO` block, action item, transition, condition clause, macro, `TAKES`/`RETURNS`, and the inner range of every EVAL body. (Alternative: a `Located` interface on the model classes; either works for the IDE.)
2. **Diagnostics instead of exceptions.** `ParseResult<T>(value: T?, diagnostics: List<Diagnostic>)` with `Diagnostic(range, severity, code, message)`. Syntax errors are collected through an `ANTLRErrorListener`; ANTLR's default recovery already yields a tree with error nodes, so the converters become tolerant (skip the broken element, emit a diagnostic, continue). Result: a partial model exists while the user is typing, which is what outline, completion and the stale diagram need.
3. **Tokenizer entry point.** `tokenize(language, text): List<Token(kind, range)>` including hidden-channel comments, with a classified kind (keyword, string, number, comment, eval-code, punctuation, error). Lexing never fails, so highlighting works on broken files, and the `//`-inside-EVAL warning can be emitted here.
4. **Printers.** `KmetaPrinter`, `KmodelPrinter`, `KstatesPrinter` (the stubs exist). The IDE needs them for fragments (print one new state, transition, type or object to insert) and for an explicit "Reformat file" action. Whole-file printing does not need to preserve comments for the IDE to work, because graphical edits are applied as local text patches, not reprints.
5. **Semantic analysis and queries** (your plan to grow analysis in dsl-tools). `AnalysisContext(metamodel, eventMetamodel?, model?, stateMachines)`, validators per language producing diagnostics with ranges, and a scope API the editor can call: `propertiesOf(type)`, `resolvePath(type, "boundingBox->position->x")`, `eventTypes()`, `statesOf(machine)`, `macrosOf(machine)`, plus stable identity helpers (`State.qualifiedName`, transition ordinal). The validation backlog of the [workspace model](#workspace-model-core-workspace) is the backlog for this layer.
6. **Housekeeping for a library consumed by an IDE.** SLF4J instead of `println`; ANTLR sources generated at build time with a `@header` package per grammar (drop the committed `src/main/java` and the package-rewrite task); packages under `io.karpfen.dsl.*`; versioned artifacts published to GitHub Packages (Maven Central later); semantic versioning tied to grammar changes.

What the IDE adds on top (`core-text`, `core-document`):

- **Text buffer** with an edit log and line index.
- **Parse scheduler**: debounce (50–150 ms), cancellation, parsing on a worker dispatcher, publishing an immutable `DocumentSnapshot(text, version, tokens, parseResult, sourceMap)` as `StateFlow`. Files are small, so full re-parse per snapshot is fine; incremental parsing is out of scope until measured.
- **Text patcher**: semantic commands (`AddState`, `AddTransition`, `MoveStateInto`, `SetActionBlock`, `ReorderTransition`, `Rename`, …) compile to `TextEdit(range, replacement)` lists using `SourceMap` anchors and printer fragments. Only inserted fragments are formatted; existing text is byte-identical outside the edited span (asserted by golden tests).
- **Model immutability by convention**: dsl-tools' classes are mutable; the IDE never mutates a parsed model (because [text files are the single source of truth](#text-files-are-the-single-source-of-truth-diagrams-are-projections-with-a-layout-sidecar), every change is a text edit).

Dependency mechanics: `settings.gradle.kts` does `includeBuild(dslToolsPath)` with `dependencySubstitution { substitute(module("io.karpfen:karpfen-dsl-tools")).using(project(":")) }`, where `dslToolsPath` defaults to `../karpfen-dsl-tools` and can point at the initialized submodule `../karpfen-runtime/karpfen-dsl-tools`; CI resolves the published artifact instead (`-PdslToolsPath=` unset). `runtime-embedded` later includes `execution-engine` the same way.

### Workspace model (`core-workspace`)

- **Project**: a folder containing the domain `.kmeta`, optional `EVENTS.kmeta`, `.kmodel`(s) and `.kstates` files, plus an **environment descriptor** (new, IDE-owned, also consumed by `tools/cli`), e.g. `cleaning_robot.kenv.json`:

  ```json
  {
    "metamodel": "cleaning_robot.kmeta",
    "events": "EVENTS.kmeta",
    "model": "cleaning_robot.kmodel",
    "stateMachines": [ { "file": "cleaning_robot.kstates", "attachedTo": "turtle" } ],
    "tickDelayMs": 50, "eventTtlMs": 3000,
    "observe": { "objects": "*", "domains": ["public"] },
    "runtime": { "image": "ghcr.io/karpfenproject/karpfen-runtime:0.x", "pythonPackages": ["numpy"] },
    "bridges": [ "mqtt-sim.kbridge.json" ]
  }
  ```

  Today the attach relation and timing exist only as HTTP parameters; making them a file is what turns "upload four files by hand" into "Run".
- **Stable identities** (layout keys, diffing, overlays): `Type(name)`, `Property(type, key)`, `Object(key)` or structural path for anonymous objects, `State(name)` (global per machine, matching runtime semantics), `Transition(kind, sources, targets, ordinal)`, `Macro(name)`. Delegates to dsl-tools helpers once they exist.
- **Snapshot diff** between consecutive parse results → change set consumed by the diagram (preserve layout of unchanged identities, auto-place new ones, drop removed ones).
- **Validation backlog for dsl-tools' analysis layer** (the language intelligence nobody has yet):
  - KMeta: unknown target types, duplicate type/property keys, invalid primitive types, root reachability via `has`, explicit-root warning (last-type convention).
  - KModel: conformance to the metamodel (class, property, relation keys; single vs list multiplicity; value parseable as declared type), unresolved `knows` keys, duplicate object keys, exactly-one-parent invariant.
  - KStates: exactly one `INITIAL`, unique state names (runtime binds first match), transitions/SPLIT/JOIN reference existing states, SPLIT ≥ 2 targets / JOIN ≥ 2 sources, `EVENT` clause first in a condition, macro existence and arity, `TAKES`/`RETURNS` types exist, `$(path)` resolution against the attached type (props/has/knows chain, `event->` against `EVENTS.kmeta`, `WITH ... AS` bindings), action arity per operation, `has` vs `knows` semantics of `SETOBJ`/`APPENDOBJ`, unreachable states, `//` inside EVAL.

### Language services (`core-lang`)

Thin adapters over dsl-tools' scope and analysis API: symbols and references, rename (state rename rewrites every transition and the layout key), completion (keywords by context, type names, property paths after `$(`, event names from `EVENTS.kmeta`, macro names), hover (type/property descriptions from `.kmeta`), folding (blocks and EVAL bodies), semantic tokens, outline, go-to (transition ↔ state, object ↔ type, macro call ↔ definition). UI-agnostic interfaces; an LSP façade (`tools/lsp-server`, LSP4J 1.0) maps onto them later.

### Notation layer (`notation-diagram`, `ui-diagram`)

- **Diagram model**: nested nodes with compartments and text cells, edges with labels and waypoints, pseudo-nodes (initial marker, fork/join bars). Geometry, hit testing and commands live here; rendering and gestures live in `ui-diagram`.
- **Views**
  - Statechart: nested state boxes; `ENTRY`/`DO` compartments as embedded editor cells; transitions labelled compactly (`EVENT public.start`, `EVAL …` truncated with hover for full text, `VALUE x`), with a priority badge because definition order is semantics; `NOT LOOPING` marker; SPLIT/JOIN drawn as fork/join bars; macros in a side table.
  - Class diagram (KMeta): type boxes with props; `has` as composition (filled diamond), `knows` as plain arrow, `list` as `*`; root type highlighted.
  - Object diagram (KModel): containment tree or nested boxes with values; reference edges; this view is reused for the live visualizer.
  - Deployment view (environment descriptor): objects with attached statecharts, runtime status, bridges.
- **Layout**: sidecar `<file>.layout.json` keyed by identity, holding positions, sizes, waypoints, collapsed flags. Elements without layout are auto-placed. Auto-layout uses **Eclipse Layout Kernel (ELK)**, `org.eclipse.elk:org.eclipse.elk.alg.layered` 0.10.0 or newer (pure Java, Java 17+; 0.11.0 is in the release plan): hierarchical layered layout with edge routing is the one place where a library saves months; the adapter is isolated so a custom layouter can replace it.
- **Runtime overlay**: a read-only layer mapping `stateChange.stack` to state node ids (active path highlighted), `objectChanged` values into object compartments, and domain events into a ticker. Overlay state never touches the document.

### Synchronization (`notation-sync`)

```
                 edit (keystroke)                        ┌──────────────┐
 text buffer ───────────────► dsl-tools parse ──► diff ──► diagram view-model ──► canvas
     ▲                        (+ SourceMap)                         │
     │  TextEdit[] (origin = diagram)                               │ gesture
     └──────────────── text-edit compiler ◄── semantic command ◄────┘
```

- A document session owns: text buffer, latest `DocumentSnapshot`, diagram view-model, layout store.
- Text → diagram: debounce, parse, diff, update; while the parse has errors the last good snapshot stays and the diagram is marked stale with the diagnostics listed.
- Diagram → text: gestures produce semantic commands, compiled to text edits and applied to the buffer as one undoable edit tagged with provenance. The diagram is then refreshed *from the text* like any other change, so there is a single writer and divergence is impossible. Pure geometry changes (drag, resize, waypoints) go to the layout store only.
- Undo/redo: one text-level stack per document; layout changes are recorded as composite commands in the same stack.
- Identity churn on rename is handled by the rename refactoring carrying the layout entry along; the diff engine uses structural position as a fallback match.

### Runtime integration (runtime modules)

**Protocol and session**

- `runtime-protocol`: one method per endpoint, DTOs with kotlinx.serialization, WebSocket session with the handshake and a typed `Flow<InboundMessage>`, reconnection, and the environment lifecycle as an explicit state machine (`Created → Configured → Activated → Running → Stopped`) mirrored in the UI.
- `runtime-session`: "Run" executes the environment descriptor: create env, upload files in order, register the IDE as observatory client (`*`), object observers for every keyed object in the `.kmodel` (until the wildcard exists), domain listeners for every domain named in `EVENT(...)` conditions and actions, then activate and start. Maintains a **live model mirror** (initial state from the parsed `.kmodel`, patched by `objectChanged`/`objectDeleted`) exposed as `StateFlow`, an **event injector** (form generated from the payload type in `EVENTS.kmeta`, or raw JSON/kmodel), and a trace/log stream.

**Managed Docker runtime (`runtime-host`, default mode)**

The IDE owns one runtime container per project workspace (one runtime already hosts many environments). Users see a status widget ("Runtime: running, image 0.x, port 51234") and never a `docker` command.

- **Engine access**: Docker Engine API over the local socket (Unix domain socket on macOS/Linux, named pipe on Windows), honouring `DOCKER_HOST` and the active docker context. Library: `docker-java` 3.5.x with the `httpclient5` transport (Apache-2.0, maintained, used by Testcontainers). A custom minimal client is feasible later (JDK 16+ has Unix-domain sockets; the API is plain HTTP/JSON) if you want to drop the dependency. Podman, Colima and Rancher Desktop expose the same API and work unchanged, which also sidesteps Docker Desktop licensing for larger organisations.
- **Image**: publish `ghcr.io/karpfenproject/karpfen-runtime:<semver>` as a multi-arch image (amd64 + arm64, needed for Apple Silicon) from the runtime repo's CI. The IDE pins a compatible tag range per IDE release, pulls on first use with progress, and verifies the version via an image label (`org.opencontainers.image.version`) until `GET /version` exists. Developer option: build the image from a local runtime checkout through the API.
- **Configuration without bind mounts**: the IDE generates `application.conf` (host `0.0.0.0`, chosen port, tick/TTL defaults, tracing to stdout) and injects it with `PUT /containers/{id}/archive` (tar upload) before start. No host directory is mounted, so Docker Desktop never prompts for file sharing. Env-var overrides upstream would make even this step unnecessary.
- **Networking**: publish the container port on `127.0.0.1:0` and read the ephemeral host port from inspect; no port conflicts, not reachable from the LAN. Bridges run in the IDE process (or the headless runner) and connect to that port, so they can still reach ROS/MQTT on the host network.
- **Ownership and reconciliation**: containers carry labels (`io.karpfen.ide.instance`, `io.karpfen.ide.project`, `io.karpfen.runtime.version`). On startup the IDE lists containers with its labels and adopts a healthy one or removes stale ones (crash recovery). Containers start with `AutoRemove`. Shutdown hook: stop all environments via the API, then stop the container with a short grace period.
- **Health and failure**: `/health` polling plus the Docker `/events` stream; if the container dies, the IDE reports it and offers a one-click re-deploy from the environment descriptor (the runtime is in-memory, so nothing else can be recovered anyway).
- **Logs and traces**: container stdout/stderr streamed with `logs?follow=1` into the Runtime tool window; trace files, if enabled, fetched from `/app/logs` with `GET /containers/{id}/archive`, again without a mount.
- **Python packages**: when the descriptor lists extra `pythonPackages`, the IDE builds a derived image (`FROM <base>` + `pip install …`) via the API, tagged by a hash of the package list and cached.
- **Resources**: CPU/memory limits from settings; a warning when `tickDelayMs = 0` (busy loop).

**Other hosts behind the same `RuntimeHost` interface**: `AttachRuntimeHost` (URL only, for a runtime started elsewhere) and `ProcessRuntimeHost` (runs the `installDist` distribution with the host JDK and host Python; preflights Python 3.12+ and packages; for machines without a container engine). Same lifecycle, health and log API for the UI.

**Later**: `runtime-embedded` depends on `execution-engine` through a composite build and adds tick stepping and transition breakpoints (needs a small step-mode hook in `Engine`).

### Bridges (bridge modules)

- `bridge-api`: `Bridge { configure(cfg); start(); stop(); health }`, inbound `Source<ExternalMessage>` and outbound `Sink<ExternalMessage>`, `Codec`, and `Mapping` (external ↔ karpfen event or object change). The core loop: external message → mapping → karpfen event (`domain`, `name`, JSON payload) over the WS session; karpfen `domainEvent`/`objectChanged` → mapping → external message. Flow-control policies matter because the engine is tick-based and events have a TTL: per-route `latest-wins`, `queue(max)`, or `sample(period)`.
- `bridge-mapping`: declarative routes in JSON/YAML (`*.kbridge.json`), e.g. MQTT topic pattern → event name with field paths, outbound templates; derives payload KMeta types from samples or from ROS message definitions (via rosbridge's `rosapi`) so the mapping is checked against `EVENTS.kmeta` at design time. A scripting hook (Kotlin script, or Python for symmetry with EVAL) is a later addition.
- `bridge-mqtt`: HiveMQ MQTT Client 1.3.x (Apache-2.0, MQTT 3.1.1 and 5, async/reactive APIs, actively released).
- `bridge-ros`: the rosbridge protocol (JSON over WebSocket; `rosbridge_suite` maintained for ROS 2 Humble/Jazzy/Rolling, BSD-3) implemented with the Ktor WebSocket client — zero native dependencies and the same JSON shape as karpfen. A native transport (pure-Java `jros2client`/`rtpstalk`, or `zenoh-java` for `rmw_zenoh`) can be added behind the same `Endpoint` interface if latency demands it.
- `bridge-runner`: headless CLI packaged with jlink/jpackage for deployment next to the robot; the IDE's bridge tool window shows routes, counters and a live message log and can test-send.

### UI shell (UI modules and the application)

- Compose Multiplatform desktop with a small custom dock/split layout (tool windows: Project, Outline, Problems, Runtime, Bridges, Log), a command registry with keymap, light/dark theme; Jewel (`org.jetbrains.jewel:jewel-int-ui-standalone`) can provide IntelliJ-style widgets but its API is explicitly unstable, so wrap it or skip it.
- `ui-editor`: custom code editor — virtualized line rendering with `TextMeasurer`/`drawText`, caret/selection/multi-caret, gutter with line numbers and diagnostics, folding, highlighting from dsl-tools tokens, inline diagnostics, completion popup, hover. The same component is embedded in diagram text cells. Bootstrapping alternative: `BasicTextField(TextFieldState)` with `OutputTransformation` for highlighting is acceptable for small files (community measurements report roughly 17 ms per keystroke off-thread at 5,000 lines) but offers poor control over caret rendering and virtualization, so it should be a stopgap behind the editor interface at most.
- Developer loop: Compose Hot Reload (stable; Kotlin ≥ 2.1.20, Compose ≥ 1.8.2) for UI iteration.

---

## Technology choices

| Concern | Choice | Alternative considered | Why |
|---|---|---|---|
| Language / JDK | Kotlin 2.3.x, JDK 21 LTS | — | Matches runtime and dsl-tools; JDK 21 is what the runtime requires |
| Build | Gradle 9 Kotlin DSL, version catalog, convention plugins (`build-logic` included build), composite build of dsl-tools | Maven | Multi-module ergonomics, same tool as the other repos |
| Language core | karpfen-dsl-tools (ANTLR 4.13) extended as described under [language core](#language-core-what-dsl-tools-must-provide-what-the-ide-adds) | hand-written parser in the IDE | Single source of truth for an evolving grammar; analysis lives in dsl-tools |
| UI toolkit | **Compose Multiplatform 1.12.x (desktop)** — decided | JavaFX 25 LTS (+ RichTextFX 0.11.7) | See table below |
| Code editor | Custom (Compose canvas) | `BasicTextField` + `OutputTransformation` | Needed inside diagram cells; full control; matches "custom first" |
| Diagram rendering | Custom Compose canvas over `notation-diagram` | Sprotty/GLSP (web) | Custom is tractable; web stacks conflict with "native" |
| Auto-layout | ELK 0.10.0+ (`alg.layered`) | custom Sugiyama; Graphviz via process | Hierarchical + edge routing is the hard part; pure Java, isolated adapter |
| Container control | docker-java 3.5.x (`httpclient5` transport) | custom client over JDK Unix-domain sockets; `docker` CLI | Structured API, log/event streaming, named pipes on Windows, Podman-compatible |
| HTTP/WS client | Ktor client 3.x + kotlinx.serialization | OkHttp + Moshi; JDK HttpClient | Coroutine-native WS, same ecosystem as the runtime |
| MQTT | HiveMQ MQTT Client 1.3.6 (2025-06) | Eclipse Paho (MQTT v5 client last released 2020) | Maintained, MQTT 5, async |
| ROS | rosbridge protocol (own client) | rcljava (JNI, unmaintained), jros2client 12.0 (pure Java RTPS, 2025-02), zenoh-java | No native deps, ROS 1 and 2, JSON; native transport later |
| LSP | LSP4J 1.0.0 (phase 5) | custom JSON-RPC | Standard, maintained |
| Testing | kotlin-test + JUnit 5, golden files, Konsist architecture tests | Kotest | Lightweight; architecture tests enforce the module boundaries |
| Packaging | Compose Gradle plugin (`packageDmg/Msi/Deb`, jpackage) | Hydraulic Conveyor (signing/updates) | Built in; Conveyor if auto-update is wanted |
| Logging | SLF4J + logback | — | Same as runtime |

Compose vs JavaFX, kept for the record:

| | Compose Multiplatform (desktop) | JavaFX 25 LTS |
|---|---|---|
| Kotlin fit | Native: declarative, coroutines-friendly state | Bindings/observables, Java APIs; fine from Kotlin |
| Code editor | Build it (canvas + `TextMeasurer`); no mature off-the-shelf | RichTextFX exists (slow-moving, works with JavaFX 25) |
| Diagram canvas | Immediate-mode `Canvas`, easy custom drawing, pointer input APIs | Retained scene graph, built-in picking, transforms, drag & drop |
| Docking/tool windows | Build it (or Jewel pieces) | Build it (third-party docking libs are stale) |
| Dev loop | Compose Hot Reload (stable) | Scene Builder/FXML; no hot reload |
| Momentum | JetBrains' own desktop apps, frequent releases (1.12.2, Oct 2026) | Steady LTS releases, smaller community |

---

## Roadmap

Since phase 1 closed, the work is planned as the [increments after phase 1](plans/increments-after-phase-1.md), which
refine the order of this roadmap.

| Phase | Scope | Done when |
|---|---|---|
| **Phase 0: skeleton** (1–2 wks) | Repo layout, `build-logic`, version catalog, composite build of dsl-tools, CI, module stubs, ADR folder, Konsist rules | `./gradlew build` green with architecture tests, dsl-tools resolved from the sibling checkout |
| **Phase 1: text** (4–6 wks, split across both repos) | dsl-tools: `SourceMap`, `ParseResult`/diagnostics, tolerant converters, tokenizer, SLF4J, build cleanup. IDE: `core-text`, `core-document`, `core-workspace`, Compose shell with project tree, custom editor (highlighting from the tokenizer, diagnostics, outline, completion for keywords/types) | All repo examples parse with ranges; broken files still highlight and outline; editor usable daily |
| **Phase 2: runtime** (3–4 wks) | `runtime-protocol`, environment descriptor, `DockerRuntimeHost` (pull, inject config, start, health, logs, reconcile), deploy/run/stop, event injector, log/trace view, model mirror; published runtime image | "Run" on the cleaning-robot example works end-to-end from a fresh machine with only Docker installed |
| **Phase 3: blended** (6–10 wks) | dsl-tools printers and scope API; `notation-diagram`, layout sidecar, ELK adapter, statechart view **read-only with live overlay first**, then editing commands + text patcher, then KMeta/KModel views | Graphical edits round-trip byte-stable except the edited span; live overlay shows active stack and values |
| **Phase 4: bridges** (4–6 wks) | `bridge-api`, mapping engine, MQTT, rosbridge, headless runner, bridge tool window | A ROS topic drives an event and an `objectChanged` publishes to MQTT without IDE involvement |
| **Phase 5: depth** | dsl-tools validators (the validation backlog of the [workspace model](#workspace-model-core-workspace)), LSP façade, packaging/signing, `runtime-embedded` stepping, upstream runtime endpoints, scripting hook for mappings | — |

Ordering rationale: a read-only live statechart (the start of phase 3) validates the whole notation layer with no synchronization risk and is the most visible payoff; the text patcher comes after the diagram exists so edit commands are designed against real gestures.

---

## Risks and mitigations

- **dsl-tools API churn while both repos move.** Pin the IDE to a dsl-tools git ref/version; the composite build makes cross-repo changes a single local iteration; publish tagged artifacts for CI.
- **Mutable model classes in dsl-tools.** The IDE treats parsed models as immutable; every change is a text edit ([text files are the single source of truth](#text-files-are-the-single-source-of-truth-diagrams-are-projections-with-a-layout-sidecar)). An architecture test can forbid calls to the `assign*`/`add` mutators from IDE code.
- **Custom editor effort in Compose.** Keep the editor interface narrow; a `BasicTextField`-based implementation is the fallback; measure per-keystroke latency from day one.
- **Patch fidelity** (comments, Python bodies, user formatting). Golden-file tests per command; byte-identity assertions outside the edited span.
- **Identity instability** (rename = new identity → layout loss). Rename as a refactoring that migrates layout; structural fallback matching in the diff.
- **Docker not present or not permitted.** Detect early with a clear message; `ProcessRuntimeHost` and `AttachRuntimeHost` as fallbacks; Podman/Colima work via the same API.
- **Image/runtime version drift.** Pin image tag ranges per IDE release; label check now, `GET /version` later.
- **Runtime API gaps** (see [the gaps](#gaps-the-ide-will-run-into-small-changes-in-your-own-repos)). Reconstruct state client-side now; land the small endpoints in parallel.

---

## Decisions record

Accepted on 2026-10-20 (the recommended option in each case):

1. Layout sidecars are committed next to their source files as `<file>.layout.json`; they are optional and regenerable.
2. The environment descriptor is IDE-owned JSON (`<name>.kenv.json`); teaching the runtime to accept such a bundle directly is a possible later addition, not a prerequisite.
3. dsl-tools exposes source ranges as a non-invasive `SourceMap` side table; the model classes stay unchanged.
4. The composite build points at `../karpfen-dsl-tools` by default, overridable with `-PdslToolsPath=` (on the owner's machine `../karpfen-runtime/karpfen-dsl-tools` is the initialized submodule). Superseded on 2026-10-26: karpfen-dsl-tools is a git submodule of the IDE repository, and that checkout is the default.
5. Bridge mapping is declarative only in phase 4; a scripting hook (Kotlin script or Python) comes later.
6. The runtime image is published from the runtime repo's CI as a multi-arch image; the IDE only pulls and pins it.

Still open (decide before the packaging work of phase 5):

- Target platforms and signing requirements (macOS notarization, Windows signing).

---

## References

- Compose Multiplatform releases: https://github.com/JetBrains/compose-multiplatform/releases ; compatibility: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Compose Hot Reload: https://github.com/JetBrains/compose-hot-reload
- Jewel (IntelliJ look and feel for Compose): https://github.com/JetBrains/jewel/ ; artifact: https://central.sonatype.com/artifact/org.jetbrains.jewel/jewel-int-ui-standalone
- Compose code-editor experiments: https://github.com/Darkrock-Studios/ComposeTextEditor/pull/117 ; https://github.com/mataku/ComposeSyntaxHighlighter ; `OutputTransformation`: https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/OutputTransformation
- JavaFX: https://www.oracle.com/java/technologies/downloads/javafx/ ; https://foojay.io/today/javafx-links-of-january-2026/ ; RichTextFX: https://github.com/FXMisc/RichTextFX
- Eclipse Layout Kernel: https://projects.eclipse.org/projects/modeling.elk/releases/0.10.0/plan ; https://projects.eclipse.org/projects/modeling.elk/releases/0.11.0/plan
- docker-java: https://github.com/docker-java/docker-java ; Docker Engine API: https://docs.docker.com/reference/api/engine/ ; JDK Unix-domain sockets (JEP 380): https://openjdk.org/jeps/380
- HiveMQ MQTT Client: https://github.com/hivemq/hivemq-mqtt-client
- rosbridge_suite: https://github.com/RobotWebTools/rosbridge_suite ; jros2client: https://github.com/lambdaprime/jros2client ; ROS Java discussion: https://discourse.openrobotics.org/t/announcing-ros2-support-for-jrosclient-java/27971 ; https://discourse.openrobotics.org/t/announcing-a-new-ros-2-compatible-java-library-jros2/51235
- LSP4J 1.0.0: https://github.com/eclipse/lsp4j/releases ; https://projects.eclipse.org/projects/technology.lsp4j/governance
- Blended modeling: Dávid et al., *Blended Modeling in Commercial and Open-source MDSE Tools: A Systematic Study* (SoSyM 2022) https://research.chalmers.se/en/publication/?id=534702 ; Latifaj, Ciccozzi, Mohlin, *Higher-Order Transformations for the Generation of Synchronization Infrastructures in Blended Modeling* (Frontiers in CS 2023) https://www.frontiersin.org/articles/10.3389/fcomp.2022.1008062/text ; Holtmann et al., *An elucidation of blended modeling from an industrial perspective* (SoSyM 2024) https://www.es.mdu.se/publications?author=266
- Demo client used as reference for runtime usage: https://github.com/karpfenproject/robot-model-js-client-demo
