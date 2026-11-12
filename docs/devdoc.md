# Developer notes

These notes are for the people who maintain the karpfen IDE. They explain how to build and test it, the conventions
the code follows, the main design decisions and the behaviour that surprises people. The [README](../README.md) gives
the short overview, the [getting started guide](getting-started.md) walks through a first model, the
[architecture document](archdoc.md) explains the modules, and [todo.md](todo.md) lists what is open. Everything else
lives in the KDoc of the types named here. Search for a type name to find its file.

## Building and testing

### Requirements

Gradle runs on JDK 17 or newer and compiles with a JDK 21 toolchain, which it downloads when it is missing. The
wrapper pins Gradle 9.8.2, and the library versions are in `gradle/libs.versions.toml`. The version of the foojay
resolver is written twice, in `settings.gradle.kts` and in the catalog. Change both together. The build needs network
access to the Gradle Plugin Portal, Maven Central and Google Maven.

The submodules must be checked out, including the nested ones, or `bundleRuntimeSources` fails:

```bash
git submodule update --init --recursive
```

Docker is only needed to run models in the IDE's own container and for the live tests. Nothing in `./gradlew build`
starts a container.

### Gradle tasks

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles everything, runs the unit and architecture tests and checks the module dependencies. |
| `./gradlew :core:core-lang:test` | Runs the tests of one module. |
| `./gradlew :app:ide:run` | Starts the IDE. Add `--args=<folder>` to open a folder. |
| `./gradlew :app:ide:runWithFailingReaders --args=<folder>` | Starts the IDE with dsl-tools readers that fail on deep nesting, to fill the Language Tools Output. |
| `./gradlew :tools:cli:installDist` | Builds the command-line tool into `tools/cli/build/install/karpfen/bin/karpfen`. |
| `./gradlew :app:ide:createDistributable` | Builds the application image without an installer. |
| `./gradlew :app:ide:packageDistributionForCurrentOS` | Builds the installer of the current platform. |
| `./gradlew :runtime:runtime-host:liveDockerTest` | Runs the host tests against the local Docker engine. |

Test reports are in `<module>/build/reports/tests/test/index.html`. The installer is a dmg on macOS, an msi on Windows
and a deb on Linux. The package version is 1.0.0 on macOS and 0.1.0 elsewhere, because jpackage rejects a major
version 0 on macOS. When a new library needs more JDK modules in the bundle, `:app:ide:suggestRuntimeModules` lists
them.

### Test tags

The convention plugin `karpfen.kotlin-jvm` excludes three JUnit tags from the normal test task. `TestTags` in
build-logic names them.

- `budget` marks time-budget tests. No task runs them, because the wall clock is too unreliable to judge them.
- `live-runtime` marks tests against a real karpfen runtime. They run only in the task `liveRuntimeTest` of
  runtime-protocol, runtime-session and ui-workbench.
- `live-docker` marks tests against the local Docker engine. They run only in `liveDockerTest` of runtime-host.

`BudgetTestTaggingTest` and `LiveRuntimeTaggingTest` check that every such test carries its tag. A test class against
a real runtime is named `Live*Test` and carries `@LiveRuntime`.

### Live runtime tests

`runtime/live-runtime.sh` runs the runtime in throwaway Docker containers and points the live tests at it. Everything
it makes carries the label `io.karpfen.ide.test=true`, and it touches nothing else. It refuses a Docker engine that is
not local, because the runtime has no authentication.

| Command | What it does |
|---|---|
| `runtime/live-runtime.sh test [gradle args]` | Builds the image, starts a runtime, runs the live tests and tears it down. Gradle arguments replace the three live tasks. |
| `runtime/live-runtime.sh up` | Starts one runtime and prints its URL and how to remove it. |
| `runtime/live-runtime.sh down [run id]` | Tears down one run, or all of them. |
| `runtime/live-runtime.sh status` | Lists the containers, volumes and images of the script. |
| `runtime/live-runtime.sh cleanup` | Removes everything the script made and reports what stays. |

The first build takes minutes. The log and the engine traces of each run stay in `build/live-runtime/runs/<run id>`.
To test against a runtime you started yourself, pass `-Pkarpfen.runtime.url=<url>` to `liveRuntimeTest`. The script
reads `KARPFEN_RUNTIME_DIR` and a few `KARPFEN_LIVE_*` variables, which its header explains.

### Where dsl-tools comes from

By default dsl-tools is a composite build of the submodule `karpfen-dsl-tools`. The property `dslToolsPath`, from
`-PdslToolsPath` or `local.properties`, points at another checkout. A blank value switches to the published artifact
from GitHub Packages, with the credentials `GitHubPackagesUsername` and `GitHubPackagesPassword`. The build needs
dsl-tools 2.0 and never reads or writes `~/.m2`.

### Command-line tool

```bash
karpfen validate <folder> [--format text|json] [--verbose]
```

It runs the same project analysis as the IDE and reports the same problems as the Problems view. The exit codes are 0
for no errors, 1 for errors found, 2 for a usage error and 3 for a failure of the tool. Positions are one-based and
paths are relative to the folder.

### Settings, logs and launch options

The IDE keeps its files in `~/.karpfen-ide`. The environment variable `KARPFEN_IDE_HOME` moves that folder.
`settings.json` holds only what belongs to the user and the machine: the recent folders and the window bounds.

Everything else belongs to the open project and is stored in `.karpfenproject` in the project folder: the theme, the
two zoom values, the tool window layout, the runtime host, the open files and the bridges of the last run. The file is
plain text with one `key = value` per line, as `ProjectSettingsFile` reads and writes it. Paths in it are relative, so
it means the same on every machine and can be committed. Reading never fails: a value that makes no sense falls back
to its default, and lines with unknown keys are kept. The file is written only when a project value changes, so a
folder that is only looked at gets no file. In memory both parts are one `IdeSettings` value, and `SettingsRepository`
keeps them apart on disk.

There are two log files in `logs/`. `ide.log` gets INFO and above from the IDE. `dsl-tools.log` gets everything
dsl-tools logs, and only its warnings and errors are forwarded to `ide.log`.

`KARPFEN_IDE_EXIT_AFTER_MS` makes the IDE exit after that many milliseconds. With a throwaway home it gives a quick
launch check, after which the log must contain no `ERROR` line:

```bash
rm -rf /tmp/karpfen-examples /tmp/karpfen-home
cp -R core/core-document/src/testFixtures/resources/examples /tmp/karpfen-examples
KARPFEN_IDE_HOME=/tmp/karpfen-home KARPFEN_IDE_EXIT_AFTER_MS=6000 ./gradlew :app:ide:run --args=/tmp/karpfen-examples
grep ERROR /tmp/karpfen-home/logs/ide.log
```

### Checking by hand

Some behaviour can only be checked in the running IDE. Start it on a copy of the examples with a throwaway home, as
above without the exit variable, and check these things on a Mac with the German input source:

- Option+8, 9, 5, 6, 7, L and N type their characters. Dead keys and the accent menu work. Option+L must not open Go
  to Line.
- Typing `{`, `(`, `[` or `"` inserts the pair. Typing the closer steps over it, and one Backspace removes both.
- Cmd+Shift+7 toggles a line comment, with `#` inside an `EVAL` body. Ctrl+M opens completion and types nothing.
- One Cmd+Z undoes exactly one step, also when chosen from the menu.
- A change made outside the IDE shows up. On macOS this takes up to about 10 seconds, because the JDK's file watcher
  polls there. An unsaved edit then gets the banner with Reload and Keep mine.
- Cmd+Q with unsaved changes asks what to do. Window bounds and recent folders come back after a restart.
- Running a model works against Docker and against a URL.

## Conventions

### Documentation

Declarations are documented in KDoc that explains what they are and what they promise. It starts with a summary
sentence, links types and members as `[Name]`, and uses tags such as `@param` only where they add something. There are
no line comments and no block comments other than KDoc. Never write the character pairs that open or close a block
comment inside comment text, because Kotlin nests block comments. Every Kotlin file and Gradle script starts with the
Apache 2.0 license header.

Markdown is kept to `README.md`, the four documents in `docs` and the license notice of the copied example files.
These documents stay short and point to the code. KDoc never sends the reader to a markdown document.

Write plain and simple English in short sentences. Name things in words, never by an id from another document or a
numbered step of a plan. Do not use the em dash, the en dash, the middle dot, the bullet or the Unicode arrows.
`DocumentationStyleTest`, `KDocPlacementTest` and `LicenseHeaderTest` enforce these rules.

### Design

The code is object-oriented and uses the classic patterns where they fit. Each module, package and class has one job.
A class stays around 300 lines, and a coordinator that grows beyond that delegates to collaborators. Seams are
interfaces with factory functions, collaborators are passed to constructors, and there are no singletons that hold
state. The application is wired by hand in its composition root. Packages are organized by feature and form no
cycles.

A factory function named like its interface creates the default implementation, such as `EditableText` or
`RuntimeClient`. In the UI modules a mutable state holder without Compose is a `*Model` in the editor and a `*State`
elsewhere, and an immutable value that a view renders is a `*UiState`. Composables contain no logic. They render a
state holder and call its methods.

Foreign libraries stay behind ports in a few adapter packages: ANTLR and dsl-tools in core-document, Ktor in
`transport.ktor` of runtime-protocol, docker-java in `docker.engine` of runtime-host, and Compose only in the ui
modules and the app. Tokenizers, parsers, readers, the project analysis and the language services never let an
exception reach the UI. A failure becomes a diagnostic or an empty answer and is logged.

Values that cross threads are immutable. Mutable state stays on one thread, which is the UI thread for document texts
and editor state. State that changes over time is exposed as a `StateFlow` or a `Flow`.

Every fact has one source. The descriptor schema is derived from `EnvironmentDescriptor`, the lexical facts of the
languages live in `KarpfenSyntax`, `mergeDiagnostics` is the one rule for merging diagnostics, `EditableText.mapRange`
the one rule for moving ranges between versions, and the runtime's wire names live only in `WireNames`.

The build enforces the structure. `ModuleDependencyRules` in build-logic holds the allowed dependencies between
modules, and the architecture tests add what a dependency matrix cannot express: `LayerImportRulesTest`,
`ForeignApiConfinementTest`, `UiLayeringTest`, `ComposeFreeStateTest`, `PackageCycleTest` and `LexicalFactsTest`. The
core, notation and runtime modules use Kotlin's explicit API mode.

### Tests

Tests use JUnit 6 with kotlin-test. Test functions are named with sentences in backticks. Coroutine code runs with
`runTest`, and Compose UI tests run headless with `runComposeUiTest`. Time is always virtual. A timeout in a test must
be at least one second, so it guards against a hang and does not measure speed.

The shared fixtures live in test fixtures. Those of core-document hold `Examples`, unmodified copies of example files
from karpfen-runtime and karpfen-dsl-tools, and `DescriptorExamples`. runtime-protocol adds the fake runtime, the
goldens and `SynthesizedModels`, the small models of the live tests.

The fakes `FakeRuntime` and `FakeDockerEngine` only return the answers each test programs. They never simulate a state
machine. Scenarios that need real behaviour run only against a real runtime, through `Live*Test` classes. No test
starts a Ktor server inside the JVM. The runtime goldens hold the exact texts the runtime writes on the wire, and
anchor tests check the client's knowledge of the runtime against the runtime's source.

The architecture tests get the submodule directories from Gradle as a system property, so they fail when run outside
Gradle. Every file in the repository root that they read must be listed in `rootFilesRead` of
`architecture-tests/build.gradle.kts`.

### Repositories and commits

karpfen-dsl-tools and karpfen-runtime are submodules. Work on them happens only in the submodules. A change is
committed in the submodule on its branch and pushed before the IDE commit that moves the pointer. Commits are authored
by Karl Kegel, with short messages and no co-author trailers. Development happens on `main` with a linear history.

A small, clear bug in the runtime is fixed on its branch with IDE support, together with a regression test in the
runtime's own tests. A bigger finding goes to [todo.md](todo.md), and the IDE copes with it in the meantime.

Work happens in small steps that are agreed before they start. A change is finished when it has been tried in the
running IDE, not only when the tests pass. Only Docker resources that carry the IDE's labels may be removed. Never
prune.

## Key design decisions

### The text files are the source of truth

The IDE edits karpfen models as text, runs them in a karpfen runtime and shows them while they run. Models live in
plain `.kmeta`, `.kmodel`, `.kstates` and `.kenv.json` files. Every view, index and analysis is derived from the text
and thrown away when the text changes. This keeps the files usable in git and in any other editor. Work by several
users on one model, running in a browser and a generic graphical editor for any metamodel are not goals.

### dsl-tools is the language core

The IDE has no grammar of its own. It runs the ANTLR grammars that dsl-tools ships for highlighting and syntax
analysis on every edit, and the dsl-tools readers for the project analysis. Semantic checks of the languages belong in
dsl-tools too. The workspace's own findings are about files, folders and descriptors. The checks before a deployment
guard against what the runtime would mishandle, such as a reference loop.

### Compose Desktop and a custom editor

The UI is Compose Multiplatform for the desktop. It fits Kotlin and coroutines directly. The text editor is written
from scratch on a Compose canvas, which gives full control over input, rendering and diagnostics. Compose is confined
to the ui modules and the app.

### Diagrams are projections of the text

A state machine has a statechart, a metamodel a class diagram and a model an object diagram. A file is shown as its
text, as its diagram or as both, and the choice belongs to the file. A diagram is derived from the outline of the
file, so it reads the text exactly as the editor does. While the text has syntax errors, the diagram keeps the last
good state and is marked as stale.

The diagram is drawn on a Compose canvas, not in an embedded web view. A web view would bring a browser along and
would save little, because the layout engine that web diagram tools use is the Eclipse Layout Kernel, which runs on
the JVM. The kernel needs the library `org.eclipse.xtext.xbase.lib` at run time, which its own package descriptions do
not name, so the version catalog lists it.

The class diagram and the object diagram share one model, `StructureDiagram`: boxes with a title and rows of text, and
links that are a containment or a reference. A relation that leads to nothing the file declares is shown as a row.
What all diagrams share is written once: `DiagramCanvas` and `DiagramEditing` work against `DiagramLayout` and
`DiagramArrangement`. A new kind of diagram needs a projection, an arrangement and a `DiagramPainter`, bundled in a
`DiagramNotation`.

The layout by hand lives in a companion file next to the source, named after it with `.vis` appended. It holds JSON
with the keys `boxes`, `bars`, `lines` and `frames`, as `VisLayoutJson` describes. A line has its waypoints and how
far its label was moved. A frame is the box of a state with inner states as the user sized it. Elements are found by stable identities: a
state by its name, a type by its name, an object by its key, a link by its relation, as in `has:Lamp.bulb`. As long as
there is no companion file, the diagram is laid out automatically. The first change by hand freezes that layout, and
from then on nothing is arranged automatically, except that a new element is placed next to the others. All stored
positions are on a grid of 10 units. If the file and the text disagree, the text wins. The companion file is a
read-only file kind, `FileKind.LAYOUT`, and the project tree shows it below its model file.

The diagram view must never take the window down. Every step of the view that runs code of a notation goes through
`DiagramFaults`: measuring, the layout, the drawing and every gesture. A step that fails is skipped or replaced, and a
note over the drawing says what failed. New code in the view follows the same rule.

A graphical edit is a small text edit, so comments and formatting survive and the text stays the only thing that is
written. A `DiagramActions` tells what the user can do at a point of a diagram, as `DiagramAction`s: one that changes
the model at once, one that first needs a click on a box, and one that first needs a name. The canvas shows them as
the context menu and runs the chain. The last action gives a `DiagramChange`, a list of text edits against the text
the diagram was derived from, which `DiagramState.source` holds. `DiagramPane` applies it as one undoable change, and
only while the document is still at that text. `StatechartEdits` makes the edits of a state machine and
`StructureEdits` those of a metamodel and a model, both with `TextBlocks`, which puts a statement at the end of a
block and takes a construct out with its lines. A new type becomes the first type of its file, because the last type
of a metamodel is its root type. A change can also say what it
means for the layout by hand: a new state is placed where the user clicked, and a renamed state keeps its place.

The notation is hybrid. The structure is drawn, and code stays text. A `DiagramCells` tells which pieces of code a
diagram shows folded up, as `DiagramCell`s: the entry and do blocks of a state and the condition of a transition. The
canvas draws a marker for each and opens a clicked cell in `CodeCellPane`. Saving goes the same way as any other
change. The pane is a plain text field so far.

### Bridges run inside the IDE

A bridge connects a running model to an external system: HTTP, MQTT or ROS. It is a plain Kotlin component with a
lifecycle that knows nothing of the user interface. The IDE configures, hosts and watches it. There is no separate
program that runs bridges.

A bridge is one file in the project, `*.kbridge.json`, as `BridgeConfigFile` describes. An inbound route turns a
message that arrives at an address into an event. An outbound route follows a property of an object, the active
states of a state machine or an event, and sends a message whenever there is something new. Each route has a flow
policy, because the engine works in ticks and events expire: a queue with a limit, only the latest message, or one
message per period.

- The HTTP bridge listens on `127.0.0.1:8090` by default and has no authentication. A `POST` or `PUT` to the path of
  an inbound route becomes its event and is answered with 202 at once.
- The MQTT bridge connects to `tcp://localhost:1883` by default and speaks MQTT 3.1.1. A broker allows one connection
  per client id, so a bridge whose file names none gets one from the name of its file.
- The ROS bridge talks to a rosbridge server, the open source package `rosbridge_suite`, which has to be installed and
  started on the ROS side. It offers the topics of a ROS system as JSON over a WebSocket, so the bridge needs no
  native ROS library. It connects to `ws://localhost:9090` by default and talks to ROS 2, or to ROS 1 with `ros1` set
  to `true`. `RosTypes` describes how a value becomes a message.

Every run of a project with bridge files asks which of them to start, and the project file remembers the choice.
`RunBridges` builds the bridges when the session is launched, starts them once the environment runs and stops them
with it. A file that cannot be read gives a bridge that failed, and the others start all the same. A bridge has no
client of its own on the runtime. `SessionModelLink` puts it on the session of the run, so its events are checked
like events sent by hand. Routes that follow a property or a state machine work only while the run observes the
model.

A bridge file is never edited as text. It opens in a form, from the project tree or through Configure Bridge in the
Run menu. `BridgeDraft` holds the texts of the fields, and `BridgeDrafts` says what keeps the draft from being saved.
Other tools may change a bridge file, so reading is tolerant.

### Coroutines and flows

All background work runs in coroutines with structured scopes. Types that wait or delay take their dispatcher as a
parameter, so tests run them on virtual time. Each open document has its own scope, and so has each runtime session.
Closing one cancels all of its work. Each `StateFlow` has exactly one writer. A `CancellationException` is always
rethrown. Cleanup that must finish, such as stopping an environment, runs without cancellation but within a time
bound.

### Environment descriptors are IDE-owned JSON

An environment descriptor (`*.kenv.json`) says which metamodel, events, model and state machines make up a runnable
environment, and how to configure it. JSON is not one of the dsl-tools languages. The IDE reads descriptors with a
tolerant JSON parser of its own, because the editor needs a tree of every text, also of a broken one. The workspace
then decodes the descriptor into `EnvironmentDescriptor`, from which the schema, completion and hover follow.

### The runtime runs in Docker or at a URL

The IDE never runs the runtime as a local Java process. It either hosts it in its own Docker container, one per
workspace, or connects to a runtime at a URL. The image is built on first use from the runtime sources that are
bundled into the IDE, so it always matches the client. It holds Python 3 with numpy, scipy, sympy, pandas, regex,
python-dateutil and chardet, which `EVAL` blocks can import.

The IDE talks to the runtime only over its HTTP and WebSocket API. runtime-protocol is a typed client of that API, and
runtime-session builds deployment, observation and event injection on it. The submodule pins the exact runtime source
the client is tested against.

### A deployment uploads the saved files

A run deploys what is on disk, never unsaved editor text. The Run action saves every open file first. The descriptor
holds the whole deploy configuration, so a run can be repeated without the IDE's state. Every deployment sends the
tick delay and the event time to live. A stopped environment is final. Running again creates a new one.

### Product choices

- A deployment listens to every event domain that the state machines use, so the run shows all their domain events.
- The default tick delay is 50 ms and the default event time to live is 3000 ms (`EnvironmentDefaults`).
- Observation is a switch that can be turned on and off while a run is live. When it is off, Pull fetches one
  snapshot.
- Code completion is Ctrl+M on every platform. Windows also binds Ctrl+Space. macOS and Linux leave Ctrl+Space alone,
  because it switches input sources there.
- The event injector edits a JSON payload that is prefilled with a template of the event's payload type.
- A model whose objects reference each other in a loop is not deployed, because the runtime cannot write it.
- dsl-tools logs to its own file and to the Language Tools Output, not to the console.

## Behaviour worth knowing

### Docker resources and how to remove them

The IDE labels everything it creates, as `DockerNames.kt` describes. Every container and image carries
`io.karpfen.ide.runtime=true`. The image tag is `karpfen-ide/runtime:` followed by 12 hex digits of the bundle's
content hash. The container name is `karpfen-ide-runtime-` followed by 10 hex digits of the hash of the workspace
path. The container binds port 8080 to an ephemeral port on 127.0.0.1 and is not removed automatically when it stops.
The host only touches what carries the label, and containers only of its own workspace. To remove everything by hand:

```bash
docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)
docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)
```

The image build also leaves an unlabelled build-stage image and the base images `eclipse-temurin:21-jdk` and
`debian:trixie-slim`.

### Starting and stopping the runtime host

Read `RuntimeHosts`. One start runs at a time, and a call that arrives during a start joins it. On start the host
adopts a matching running container of the workspace and removes outdated ones. It polls the health endpoint for up to
120 s, because the JVM and the first Python start are slow. A watcher inspects the container every 5 s and marks the
host failed with `CONTAINER_DIED` when the container is gone. The runtime keeps its environments only in memory, so
the runs on a died container have to be run again. Only `unix://` and `npipe://` Docker endpoints are accepted.

On exit the IDE first shuts down every live session, for at most 5 s, then the host stops its container. The whole
shutdown is bounded at 15 s. `RuntimeSession.shutdown()` stops the environment, `close()` returns at once and leaves
it as it is.

### Runs in the Runtime tool window

Read `RuntimeState`. Running a descriptor again shuts down its live runs, whose entries stay in the list as stopped.
Changing the runtime settings stops the live runs and replaces the host. The tool window keeps 5,000 log lines, 10
runs and 500 events per run. Run is Shift+F10 and Stop is Ctrl+F2, or Cmd+F2 on macOS. Run picks the descriptor it was
given, the one in the active editor, the only one in the workspace, or the one the user picks. Run gives the Runtime
tool window three quarters of the workbench's height.

The IDE's container runs the runtime with engine tracing on, because the trace is the only place where the runtime
tells which transition fired. The engine writes several trace lines on every tick. `RuntimeHostBinding` drops those
lines, as `EngineTrace.isTickNoise` decides. The simplified log of `StatechartLog` shows the remaining trace lines in
a few words each.

Two zoom controls change `uiScale` and `editorFontSize`. `Workbench` applies `uiScale` by scaling the density, so
text, rows and icons scale together. `EditorArea` draws the editor at the window's own density, so the two sizes are
independent.

### Deployment and observation

Read `DeployStepKind` and the `deploy` package of runtime-session. A deployment checks the runtime, creates the
environment, connects, uploads the files, attaches the state machines, sets the timing, listens to the domains,
activates, starts observing and starts. Problems carry a code, such as `deploy-unsaved-file` or
`deploy-reference-loop`.

The runtime reads uploads with its older parser, so a text that the IDE reads cleanly may still be rejected without a
position. The run is blocked on the IDE's own findings before anything is uploaded, `FailureLocator` maps the known
texts of the runtime's parser to the declaration they name, and the first snapshot is compared with the model the IDE
analyzed. Each object that differs gets the warning `deploy-model-differs`.

Read `ObservationReducer`. A mirror starts from a snapshot and then applies pushes in revision order. A gap in the
revisions or a reconnect triggers a fresh snapshot. The runtime answers a snapshot only at a tick boundary and waits
up to 10 s for one, so snapshots get slow on a large model or with long Python calls in a tick.

### What the runtime client copes with

Read `DefaultRuntimeConnection`, `LifecycleRules` and `RuntimeJson`.

- The client refuses calls that the runtime would mishandle in the environment's current phase, and sends nothing.
- The runtime writes `NaN`, `Infinity` and `-Infinity` as bare literals. `RuntimeJson` accepts exactly these.
- The runtime keeps pushing to the first socket of a client id, so before a reconnect the client closes the old
  socket. A half-open old socket still swallows pushes until the runtime's ping drops it. The model then
  resynchronizes, at worst about 75 s late, and domain events lost in that time are not recovered.
- Forcing a state makes the runtime discard all queued external events of the environment.

### Keyboard handling

Read `ProxyTextInput`, `LayoutAwareStrokes` and `defaultKeyBindings`. The editor gets its text through a hidden text
field at the caret, so input methods, dead keys and the accent menu work. A stroke goes to completion first, then to
the editor's keymap. The menu bar is the only place that dispatches window shortcuts, and the menus and the editor
take their strokes from the same keymap. `LayoutAwareStrokes` maps punctuation strokes by the character they type, so
Cmd+Shift+7 on a German layout acts as Cmd+/.

On macOS the app sets `apple.eawt.quitStrategy` to `CLOSE_ALL_WINDOWS`, so Cmd+Q runs the window's close request with
the unsaved-changes dialog.

Python code in an `EVAL` body uses `#` for comments. The editor warns about `//` there, and Toggle Comment uses `#`
inside an `EVAL` body.

### Texts, parsing and files

Read `EditableText.mapRange`. It is the one rule for moving a range through later edits, and it returns null for a
range older than the kept history of 1000 versions. Typing within 1 second coalesces into one undo step.

Read `DocumentSessionConfig`, `WorkspaceOptions` and `NestingLimit`. Open documents are parsed in syntax-only mode.
Only the project analysis reads files with dsl-tools, and only files without syntax errors. So the IDE and the
command-line tool agree, and opening or closing a file changes no finding. The parse waits 120 ms after the last edit,
the file watcher 200 ms and the project analysis 300 ms. Blocks nested deeper than 300 levels get `nesting-too-deep`
instead of a parse that could overflow the stack.

Read `SourceLoader`, `AtomicTextFileStore` and `DiskSynchronizer`. Line separators and a byte order mark are
normalized on load and restored on save. Saving writes a temporary file and moves it over the target in one step, so
a crash never leaves a truncated file. A change on disk to a document without unsaved edits reloads it as one undoable
change. A document with unsaved edits gets a banner with Reload and Keep mine.

### The Project tool window

The tree has three sections, divided by lines, as `ProjectSection` and `ProjectTreeNodes` decide: the project file and
the environment descriptors, then the folder with everything else, then the bridge files. The kind of a file decides
its section, so a descriptor or a bridge file in a subfolder is listed in its section by its path and not in its
folder. The line is part of the row below it, because the rows of a `TreeView` all have the same height.

### Descriptors and modeling contexts

Read `DescriptorValidator` and `ContextResolver`. A known key that appears twice in a descriptor is an error. An
unknown key is only a warning, so a descriptor written for a newer runtime still opens.

Each descriptor defines one context of the files it names. Every directory without a descriptor forms an implicit
context of the karpfen files directly inside it. Its metamodel is the one `.kmeta` file that is not `EVENTS.kmeta`,
and its events file is `EVENTS.kmeta`, matched ignoring case. Files that belong to no context are loose files. They
are tokenized and parsed, but dsl-tools never reads them.

### Common changes

- A new module needs an entry in `modulePaths` of `settings.gradle.kts`, a rule in `ModuleDependencyRules` and an
  entry in `KarpfenModules` of the architecture tests.
- A new runtime endpoint starts on the runtime's branch with IDE support. On the IDE side it goes into the catalogue
  (`RuntimeRoute`, `WireNames`, `RuntimeText`) and gets a function of `RuntimeApi`, the goldens, an anchor in
  `RuntimeSourceAnchorTest`, a row of `RuntimeContract` and a live run.
- Moving karpfen-runtime to another commit makes the anchor tests fail exactly where the runtime changed. Update the
  catalogue, the defaults of `FakeRuntime` and the goldens, run `runtime/live-runtime.sh test`, and only then update
  the anchors.
- A new descriptor key goes into `EnvironmentDescriptor`, with a `@KeyDoc`. Deployment and `EnvironmentTemplate` must
  be updated by hand.
- A new editor action needs an id and a title in `EditorActionIds` and `EditorActionTitles`, an implementation in
  `defaultEditorActions`, a binding in `defaultKeyBindings`, a command in `EditorCommands` and a place in `IdeMenus`.
- A new language needs one `LanguageSupport` class and an entry in `defaultLanguageRegistry`, and its editor features
  as a `LanguageFeatures` in `karpfenLanguageFeatures`.
- A new kind of diagram needs a `DiagramNotation` and an entry in `EditorViewMode.diagramOf`.
