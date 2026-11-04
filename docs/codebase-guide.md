# Codebase guide

This guide is a map of the karpfen IDE code. For each feature it names the module, the package and the types to start
reading, and the order in which they make sense. It does not repeat what the code says: algorithms, invariants,
threading rules, edge cases and the reasons behind a design are documented in the KDoc of the types named here, next to
the code. How to build, run and test is in the [README](../README.md); why the architecture looks the way it does is in
the [architecture decision records](adr/README.md).

Package names below are relative to their module. Every module keeps its code below one package prefix, made of
`io.karpfen.ide`, the layer and the rest of the module's name, so "the `session` package of core-document" is
`io.karpfen.ide.core.document.session`. Three modules have shorter prefixes: app/ide uses `io.karpfen.ide.app`,
architecture-tests `io.karpfen.ide.architecture` and build-logic `io.karpfen.ide.buildlogic`. `KarpfenModules` of the
architecture tests lists the prefix of every module of the product build, and the architecture tests check them;
build-logic is an included build of its own and keeps its prefix by convention only.

## The big picture

karpfen IDE is a Gradle build of Kotlin/JVM modules in layers, and dependencies only point downwards:

- **core** is the language core and knows nothing about a UI. core-text holds the text of a document, core-document
  turns text into tokens, parse results and diagnostics on top of karpfen-dsl-tools (and, for environment descriptors,
  on top of its own JSON parser), core-workspace manages an opened folder with its modeling contexts and their
  analysis, and core-lang answers editor questions such as completion, hover and go to definition.
- **ui** is built with Compose Multiplatform for Desktop. ui-foundation holds the theme, widgets, commands and the key
  model, ui-editor the code editor, and ui-workbench the window content: project tree, editor tabs, outline, problems,
  the output of the language tools and status bar.
- **app/ide** is the desktop application and its composition root.
- **tools/cli** is the `karpfen` command-line tool. It validates a folder with the same workspace code as the IDE.
- **runtime** is the IDE's side of the karpfen runtime: runtime-protocol, the typed client of the runtime with IDE
  support, and runtime-session, which deploys an environment descriptor from a workspace and keeps a session with the
  deployed environment. Neither has a user interface yet.
- runtime-host, **notation**, **bridges** and ui-diagram belong to later increments and are stubs so far.

An edit travels through the layers like this: the editor applies it to the document's text (core-text); the document
session tokenizes and parses the new version (core-document); the workspace analyzes the modeling context again and
updates the problems (core-workspace); the language services answer the editor's questions about the new text
(core-lang); the editor and workbench state holders publish new UI state, and the composables draw it (ui).

Throughout the code, state is published as `StateFlow`, background work runs in coroutine scopes that are cancelled
when their owner closes, and only immutable values cross threads. The
[decision on the reactive backbone](adr/0009-coroutines-flow-reactive-backbone.md) explains that choice, the
[decision that text files are the single source of truth](adr/0002-text-files-are-the-single-source-of-truth.md) why
every change is a text edit.

## From a keystroke to a highlighted, parsed document

To understand how a keystroke becomes a highlighted, parsed document, read these types in order. The path touches most
modules, so it is the best first tour of the code.

1. **Keyboard input.** `ProxyTextInput` in the `view.input` package of ui-editor receives typed text through a hidden
   text field, which is how every keyboard layout, dead key and input method reaches the editor. It hands key strokes
   to `CodeEditorState.handleKey` and committed text to `CodeEditorState.type`.
2. **Editor actions.** `DefaultCodeEditorState` in the `state` package turns both into editor actions: typed text
   becomes `typeTextAction`, a key stroke is looked up in the `EditorKeymap` and runs one of the actions of
   `defaultEditorActions` (package `model.action`). Every action runs through `ActionRunner`; an action that edits
   applies its edits to the document's text in one undo step.
3. **Text.** `EditableText` in core-text applies the edits atomically, records the undo step in its `UndoManager` and
   publishes the new immutable `TextSnapshot` together with a `TextChange`.
4. **Document session.** `DocumentSession` in the `session` package of core-document follows the text:
   `TokenizePipeline` tokenizes every new version at once, `ParseScheduler` parses after a short quiet time, and
   `DiagnosticsMerger` maps all diagnostics onto the newest version. `DefaultDocumentSession` shows how they are wired.
5. **Tokenizer and parser.** The session gets its `Tokenizer` and `DocumentParser` from the `LanguageSupport` of the
   document's language; `defaultLanguageRegistry` in the `registry` package wires the three modeling languages and
   environment descriptors.
6. **Highlighting.** `HighlightPipeline` in the `state` package of ui-editor combines the new tokens with the latest
   parse through `LanguageServices.highlight` of core-lang and publishes the highlight spans.
7. **Drawing.** `CodeEditor` in the `view` package renders the editor state. Its text layer `EditorTextArea` draws
   with `TextAreaPainter`, which lays out the visible lines (`LineLayouter`, cached in a `LineLayoutCache`) and paints
   text, carets, selections and diagnostics.

In parallel, the workspace picks up the new text of the open document and analyzes its modeling context again; the
section on the workspace below continues from there.

## Text model (core-text)

Start at `EditableText` and its factory function: the mutable text of one document, edited by one thread. Then read
the values it works with: `TextSnapshot` (one immutable version, with its `version` and `lineage`), `TextEdit`,
`TextChange` and `EditOrigin` (why a change was made, which drives undo coalescing). `UndoManager` documents what one
undo step is, and `LineIndex` maps offsets to lines and columns.

`EditableText.mapOffset` and `EditableText.mapRange` carry positions computed for an older version onto the current
text. Every layer uses them to keep carets, highlights and diagnostics in place while the user types; `TextLineage`
tells which text a version belongs to. `DefaultEditableText` is the implementation, a facade over `TextVersions`,
`DefaultUndoManager` and `ChangePublisher`.

## Tokenizing and parsing (core-document)

- **Languages.** `KarpfenLanguage` names the languages: KMeta, KModel and KStates, the modeling languages, and
  environment descriptors. `LanguageRegistry` and `LanguageSupport` (package `language`) give each language its
  tokenizer and parser; `defaultLanguageRegistry` (package `registry`) wires them and explains how a further language
  is added.
- **Tokens.** `Tokenizer`, `SyntaxToken` and `TokenKind` (package `syntax`) are the token model. `KarpfenSyntax` holds
  the lexical facts that the tokenizers, the language services and the editor must agree on.
- **Parse outcomes.** `DocumentParser` and `ParseOutcome` (package `parse`) describe what a parse yields: the outline
  (`OutlineNode`), declarations and references (`SyntaxIndex`), folding regions and semantic spans. `NestingLimit`
  says how deep a text of a modeling language may nest before it is reported instead of parsed.
- **ANTLR adapter.** The `antlr` package is the only code that touches ANTLR and the parsers generated in
  karpfen-dsl-tools. Its `lex` sub-package holds the tokenizers (`KstatesTokenizer`, and `GapRecoveringTokenizer` for
  KMeta and KModel), its `tree` sub-package the parse-tree walkers (`TreeParser`, `KstatesSyntaxAnalyzer` and their
  KMeta and KModel counterparts). `KarpfenLexers` creates the lexers, and `EvalBodies` finds the embedded Python of
  `EVAL` bodies.
- **dsl-tools adapter.** The `dsl` package wraps the tolerant readers of dsl-tools (`DslReader`): a read yields the
  model, partial when the text has errors, and the findings at the places the reader reports (`ReadResult`), which
  `ReadResults` turns into DSL_TOOLS diagnostics with the stable codes of dsl-tools. `DslToolsLoggers` names the parent
  logger below which dsl-tools logs, the name by which the IDE and the command-line tool route that logging.
- **JSON.** The `json` package reads JSON the way the editor needs it, written for the IDE rather than taken from a
  library: `JsonLexer` and `JsonTokenizer` split it into tokens, `JsonParser` builds a `JsonDocument` whose `JsonNode`
  tree keeps every range and survives broken text (`JsonTreeBuilder` documents how it recovers).
- **Environment descriptors.** The `environment` package is the descriptor language. The typed
  `EnvironmentDescriptor` is the one declaration of the descriptor's shape, and `EnvironmentSchema` derives the
  schema every other reader uses from it: the keys with their nesting and value shapes (`ValueShape`,
  `DescriptorKey`), which are required, and what role a named file plays (`DescriptorFileRole`). `shapedValues` walks a
  descriptor along the schema, and `EnvironmentSyntaxAnalyzer` turns a descriptor into outline, folding, semantic
  spans and syntax diagnostics.
- **Diagnostics.** `Diagnostic` and `DiagnosticCodes` in the root package describe a problem; `mergeDiagnostics` in
  the `session` package is the one rule that combines the diagnostic sources of a document.

## Workspace and project analysis (core-workspace)

Start at `Workspace`, the facade over one opened folder, whose KDoc lists its pipelines, and at `WorkspaceFactory`,
which the IDE and the CLI use to open folders. `DefaultWorkspace` coordinates the collaborators of the packages
below, which the `Workspace` factory function creates.

- **Files.** The `files` package scans the folder (`WorkspaceScanner`, `WorkspaceTree`, `FileKind`) and watches it
  (`FileWatcher`, `WatchServiceFileWatcher`).
- **Documents.** The `document` package opens, saves and reloads documents: `DocumentManager` and `OpenDocument`,
  `SourceLoader` for reading files, `AtomicTextFileStore` for writing them, and `DiskSynchronizer` for changes made
  outside the IDE and the conflicts they cause.
- **Environment descriptors.** The `environment` package reads `*.kenv.json` files: `JsonEnvironmentDescriptorReader`
  parses them with core-document's JSON parser, checks them against `EnvironmentSchema` with `DescriptorValidator`
  and decodes them into core-document's typed `EnvironmentDescriptor`. `DescriptorPaths` is how a descriptor names
  files, `FileKind.fits` which files fit the role of a key, and `DescriptorFiles` whether a named file is there: the
  one rule that the contexts and the editor's hover, go to definition and completion of descriptors share.
- **Modeling contexts.** The `context` package decides which files are analyzed together: `ModelingContext`, and
  `ContextResolver` with the rules for descriptors, directories and loose files. `ContextTracker` follows the
  descriptors open in editors (`OpenDescriptors`) and reads them from their editor texts, so their findings, the
  contexts and the analysis follow unsaved edits.
- **Analysis.** The `analysis` package reads every context with dsl-tools: `ProjectAnalyzer` documents the rules,
  `DefaultProjectAnalyzer` implements them with `ContextReader`, which reads only what changed since the last run
  (`RememberedReads`) and names the file it reads in the logging context, `ProjectAnalysis` is the result, and
  `AnalysisScheduler` runs the analysis again, debounced and cancellable, when the contexts or the open documents
  change. `ContextSymbols` answers what the analyzed files declare that descriptors refer to (objects, `ATTACHED TO`
  types, event domains); the descriptor's language services and the template of new descriptors both ask it.
- **Problems.** The `problems` package applies one rule to every diagnostic of every file: `WorkspaceProblems` and
  `WorkspaceProblemsAggregator`. The Problems view, the status bar and `karpfen validate` all use it unchanged, which
  is why the IDE and the CLI always agree.

## Language services (core-lang)

Start at `LanguageServices`, the facade for highlighting, completion, go to definition, references, hover and bracket
matching, and at `LanguageDocument`, its immutable input, whose KDoc explains how parses older than the text are used.
`defaultLanguageServices` in the `services` package creates the facade for one workspace.

- **Per language.** `LanguageFeatures` (package `feature`) is the family of providers of one language;
  `karpfenLanguageFeatures` lists them. The packages `kmeta`, `kmodel` and `kstates` hold the providers, for example
  `KstatesCompletionProvider`; the package `environment` holds those of descriptors, which find the place of the caret
  with `DescriptorCaret`, how a proposal keeps the JSON valid there (quotes, missing commas) with `SlotEdits`, and what
  the named files declare with `DescriptorProject`.
- **Shared mechanics.** `CaretContextDetector` (package `context`) finds out what surrounds the caret, `TypeCatalog`
  (package `symbols`) knows the types of a modeling context, `ModelPathResolver` (package `modelpath`) resolves model
  paths such as `$(boundingBox->position)`, `DefaultHighlightProvider` (package `highlight`) maps tokens and semantic
  spans to highlight styles, `TokenBracketMatcher` (package `brackets`) matches brackets, and `ContextReferences`
  (package `navigation`) finds references across the files of a context.
- **Values.** `HighlightStyle` and `CompletionItem` in the root package are what the editor receives.

## Editor model and view (ui-editor)

Start at `CodeEditorState` in the `state` package: the state holder of one editor and its factory function. It
combines `EditorViewModel` (what the view renders), `EditorPopupModel` (the popups) and `EditorHandle` (what the
workbench uses). `DefaultCodeEditorState` lists its collaborators, each with one responsibility.

- **Model.** The `model` packages are free of Compose and tested without a UI. `model.action` holds the editor actions
  as commands (`EditorAction`, `defaultEditorActions`, `EditorActionIds`, `EditorActionTitles`) and the shortcuts
  (`EditorKeymap`, `defaultEditorKeymap`); `model.caret` the carets (`CaretModel`); `model.completion` the completion
  session (`CompletionSessionModel`); `model.find` find and replace (`FindModel`); `model.navigation` go to line
  (`GoToLineQuery`).
- **State.** Next to `DefaultCodeEditorState`, the `state` package holds `ActionRunner`, `HighlightPipeline`,
  `SymbolLookup`, `HoverController` and `DefinitionNavigator` for hover and go to definition,
  `EditorCompletionSource`, and `EditorViewport` for scrolling.
- **View.** The `view` package holds the composables: `CodeEditor`, `EditorTextArea`, `EditorGutter`, the painters
  `TextAreaPainter` and `GutterPainter`, and the `LineLayoutCache`. Its sub-packages handle keyboard and mouse input
  (`view.input`: `ProxyTextInput`, `textAreaPointerInput`), geometry, rendering details, scrollbars and the clipboard.
- **Popups.** The `popup` package holds the completion list, the find and replace bar, go to line, the hover tooltip
  and the context menu (`EditorPopups`, `CompletionPopup`, `FindReplaceBar`). The view places them through the slots
  of `EditorOverlays` (package `overlay`), so view and popups do not depend on each other.
- **Theme.** `EditorTheme` (package `theme`) maps highlight styles to colours.

## UI foundation (ui-foundation)

ui-foundation holds the building blocks that the editor, the workbench and the application share; it knows no IDE
feature.

- **Widgets.** The `widgets` package holds the generic widgets the workbench draws with: `TreeView` with its
  `TreeNode`, the lazy tree behind the project tree, the outline and the problems, with keyboard navigation and context
  menus; `TabStrip` for the editor tabs; `StatusBar`; and the tooltip and context menu they share.
- **Layout.** `SplitPane` and `ToolWindow` in the `layout` package divide the window and frame each tool window.
- **Theme and icons.** `KarpfenTheme` (package `theme`) provides the light and dark palettes and the typography, and
  matches the Material widgets to them; `KarpfenIcons` (package `icons`) draws the file, severity and action icons.
- **Commands and keys.** `CommandRegistry` (package `command`) holds the application's commands and menus; `KeyStroke`,
  `KeyShortcut` and `HostPlatform` (package `keys`) are the toolkit-neutral key model, and `keys.compose` converts
  Compose key events and menu shortcuts.

## Workbench (ui-workbench)

Start at `WorkbenchState` in the `state` package: the mediator that owns the opened workspace and its language
services and coordinates the feature states. `DefaultWorkbenchState` lists the flows it delegates to, such as
`FileOpenFlow`, `SaveFlow` and `EditorLifecycle`. `WorkbenchServices` holds its collaborators;
`defaultWorkbenchServices` in the root package is the production wiring, and the `Workbench` composable lays out the
window.

Each feature has a package with a Compose-free state class and, where it draws something, a `view` sub-package with
its composables: `projecttree` (`ProjectTreeState`), `tabs` (`EditorTabsState` and the banner for changes on disk),
`outline` (`OutlineState`), `problems` (`ProblemsState`), `statusbar` (`StatusBarState`), `dialog` (`DialogState`),
`layout` (`LayoutState`, with `ToolWindowId` for the tool windows) and `output` (`OutputState`); `welcome` holds the
view shown when no folder is open.

The `output` package is the Language Tools Output, the tool window that shows what the language tools log. Its port
has two roles: the application's logging writes to an `OutputSink`, the workbench reads an `OutputBuffer`, and
`RingOutputBuffer` is both, a thread-safe ring buffer that the composition root creates and passes in through
`WorkbenchServices.languageToolsOutput`. `OutputState` follows it and counts the errors and warnings that arrive while
the tool window is hidden; `SubStateBindings` in the `state` package passes them on to the status bar's indicator.

The other packages: `settings` stores the IDE settings (`IdeSettings`, `SettingsRepository`, `SettingsLocations`),
`templates` creates new files (`FileTemplates`, `EnvironmentTemplate`), `files` defines the desktop file operations
(`ProjectFileOperations`, `FolderChooser`) with their adapters in `files.desktop`, `files.view` holds the file icon
that the project tree and the editor tabs share (`fileIconOf`), and `command` names the workbench commands
(`WorkbenchCommandIds`).

## Application and composition root (app/ide)

`main` resolves the home directory for settings and log and the launch options (`LaunchOptions` in the `launch`
package), then starts `IdeApplication`. That class is the composition root: plain Kotlin that wires the
`WorkbenchState` over `defaultWorkbenchServices`, a `CommandRegistry` with the menus of `IdeMenus`, and the Compose
window (`IdeWindow.kt`, with `IdeRunState` for the life of one run). `CrashHandling` and the `crash` package turn
unexpected exceptions into a log entry and an error dialog, the `window` package keeps the window bounds, and the
`menu` package resolves the menus against the registered commands.

Logging is configured in `app/ide/src/main/resources/logback.xml`, which also keeps what dsl-tools logs off the console
and writes it to a log file of its own. The `logging` package holds the seam `LanguageToolsLogRouting`, which the
composition root takes in its constructor and attaches for the life of a session, and the logback adapters:
`LogbackLanguageToolsLogRouting` attaches `OutputSinkAppender` to the dsl-tools logger, so its events reach the
workbench's Language Tools Output, and `DslToolsThresholdFilter` lets only the warnings and errors of dsl-tools into the
IDE log.

The commands and the key model come from ui-foundation, described above. The editors and the menus take their
shortcuts from the same `EditorKeymap`, so they always agree.

## Command-line tool (tools/cli)

`main` hands the arguments to `KarpfenCli`, which parses them with `CliArguments` and returns an exit status from
`ExitCodes`. `ValidateCommand` in the `validate` package opens a `Workspace` without watching, waits for the analysis
and reports `Workspace.problems`. `ValidationReport` and `ReportFormatter` turn the problems into text or JSON, which
`CliConsole` prints on standard output. What dsl-tools logs is off in the tool's `logback.xml`; with `--verbose`,
`LanguageToolsLog` in the `logging` package shows it on standard error for the length of the validation, as the
[decision that dsl-tools output stays off the primary console](adr/0014-dsl-tools-output-stays-off-the-primary-console.md)
asks.

## Runtime client (runtime-protocol, runtime-session)

Start at `RuntimeClient` in the root package of runtime-protocol and at `RuntimeSessions` and `RuntimeSession` in the
root package of runtime-session. A client checks a runtime, creates environments and opens connections; a session
deploys one prepared environment descriptor through a client and is the facade over everything that follows. The
[decision on the runtime client](adr/0016-the-runtime-client-talks-to-the-runtime-with-ide-support.md) and the
[decision on deploying](adr/0017-deployment-uploads-the-saved-files.md) explain the design.

runtime-protocol depends on no other module of the IDE, so the bridges can build on it, and both modules declare their
API explicitly. Its packages:

- **`catalogue`**: the wire contract as data. `RuntimeRoute` lists the runtime's routes with their parameters,
  `RuntimeText` the texts of its rejections with their `RejectionKind`, `WireNames` the names on the wire, and
  `EnvironmentKey`, `ClientId`, `AccessKey` and `RuntimeEndpoint` the values the runtime hands out or is reached at.
- **`transport`**: the two seams the IDE owns, `RuntimeHttp` and `WebSocketConnector` with its `WebSocketLink`, bundled
  as `RuntimeTransports`. **`transport.ktor`** holds their Ktor adapters behind `ktorTransports`, the only Ktor code.
- **`message`**: the runtime's JSON, read by `RuntimeJson` into `WireValue`s; the pushes (`RuntimePush`), which
  `RuntimeFrameDecoder` decodes with every `ProtocolViolation`; the model snapshot (`RuntimeModelSnapshot`); and the
  event frames to the runtime (`EventFrame`, written by `EventFrameEncoder`).
- **`api`**: `RuntimeApi`, the raw gateway with one function per route over the HTTP seam, whose `RuntimeResult`s and
  `RuntimeFailure`s never throw.
- **`lifecycle`**: `RuntimeEnvironment`, the handle of one environment, which keeps its `EnvironmentPhase` on the
  client's side, asks `LifecycleRules` before every call and refuses what the runtime would mishandle.
- **`connection`**: `RuntimeConnection`, the authenticated push channel of one client to one environment, with its
  `ConnectionState`, its `ConnectionEvent`s and the `ReconnectPolicy`.

runtime-session's packages, in the order a deployment uses them:

- **`problem`**: `DeployProblem`, `DeployProblemCodes` and `SourceLocation`, the problems of preparing, deploying and
  running.
- **`plan`**: `DeploymentPlanner` turns the analysis of the saved workspace into a `DeploymentPlan` with its
  `DeployConfiguration` and `DeclarationLocations`, or into the problems that block it; each check is a `PlanCheck`.
- **`deploy`**: `Deployer` runs the steps of a deployment (`DeployStep`, run by `DeployPipeline`) with progress,
  locates failures (`FailureLocator`) and cleans up (`EnvironmentCleanup`); `DeployObservation` is the port to the
  session's observation, and `EnvironmentLedger` records every environment left on a runtime.
- **`schema`**, **`mirror`** and **`states`**: `TypeSchema`, the metamodel as values; `MirrorSeeder` and
  `MirrorReducer`, which keep the model as `MirrorSnapshot` values, and `MirrorComparison`; `MachineLayout` and
  `ActiveStatesReducer`, the active states of the machines.
- **`observation`**: the live switch. `ObservedModel` is what a session shows; `ObservationReducer` holds the rules and
  `ModelObserver` carries them out.
- **`events`**: `EventCatalogBuilder` builds the `EventCatalog` of the domains the machines use, `PayloadFormat` and
  `PayloadForm` check and write payloads, and `EventInjector` injects events.
- **Root package**: `RuntimeSessions` prepares a deployment (`DeploymentPreparer`, which saves first) and launches a
  session. `DefaultRuntimeSession` composes two mediators: `SessionConductor`, which carries out the commands in order
  and is the single writer of the session's state, progress and problems, and `PushRouter`, the only reader of the
  connection's events and the writer of the observed model, the domain events and the journal. `PhaseStates` and
  `CommandRules` are the tables of the session's states and commands.

The tests of both modules run against `FakeRuntime` in the `fake` package of runtime-protocol's test fixtures, which
never simulates the runtime: it answers only the responses and frames each test programs. `RuntimeGoldens` and
`RuntimeWireWriter` in the `golden` package hold the runtime's wire format, and the `source` package reads the runtime's
sources, so that `RuntimeSourceAnchorTest`, `RouteCompletenessTest` and `TemplateGoldenTest` find every fact the client
relies on in the karpfen-runtime submodule. The scenario classes, `RuntimeContract` and `ConnectionScenarios` in
runtime-protocol and `DeploymentScenarios`, `InjectionScenarios`, `ForcedStateScenarios`, `ObservationScenarios`,
`RunTimeObjectScenarios` and `MultiEnvironmentScenarios` in runtime-session, run only against a real runtime, through
their `Live…Test` subclasses tagged with `@LiveRuntime`; failures only a fake can produce are ordinary tests against the
fake, such as `RuntimeHazardsTest` and `RuntimeSessionFailureTest`. The `conformance` package of the fixtures holds
what the scenarios share: `RuntimeUnderTest` with `LiveRuntimeUnderTest`, `Patience`, the waits `eventually` and
`awaitValue`, and `SynthesizedModels`, the small models in its resources; runtime-session adds `SessionUnderTest`. The
`liveRuntimeTest` task of `karpfen.live-runtime-test` runs them against the runtime that `runtime/live-runtime.sh`
starts in a throwaway Docker container, as the [README](../README.md#checking-the-client-against-a-real-runtime) and the
[decision on testing runtime work](adr/0018-runtime-work-is-tested-against-an-anchored-fake-and-a-throwaway-runtime-container.md)
describe. runtime-session's fixtures add `ModelWireText`, which writes an analyzed model as the runtime does, and
`FakeRuntimeSession`, a session the tests of views can script.

## Build, architecture rules and tests

- **Build logic.** `build-logic` holds the convention plugins `karpfen.kotlin-jvm` (applied by every module),
  `karpfen.kotlin-serialization`, `karpfen.compose`, `karpfen.application` and `karpfen.live-runtime-test` (the live task
  of the runtime modules, named in `LiveRuntimeTesting`), the module dependency matrix `ModuleDependencyRules` with its
  check `VerifyModuleDependencies`, and `TestTags`. Versions live in
  `gradle/libs.versions.toml`; `settings.gradle.kts` decides how karpfen-dsl-tools is resolved.
- **Architecture tests.** The `architecture-tests` module checks the layering with Konsist: `LayerImportRulesTest`
  (which libraries each layer may use, Ktor in the runtime only in the adapters of runtime-protocol),
  `ForeignApiConfinementTest` (ANTLR, dsl-tools beyond its semantic model and logback only behind their adapters),
  `ComposeFreeStateTest` and `UiLayeringTest` (state holders without Compose, views on top), `PackageCycleTest` (no
  cycles, and no package of a runtime module imports the module's root package), `PackageLayoutTest`,
  `LexicalFactsTest` (one declaration per lexical fact), `BudgetTestTaggingTest` and `LiveRuntimeTaggingTest` (the live
  tag and task stay in the runtime modules and out of every other build and CI run).
  `KarpfenModules` is the module table these tests share. `DocumentationStyleTest` guards the documentation style in
  every Kotlin source file and Gradle script, with `KotlinComments` to find the comments, `KDocPlacement` to check
  that KDoc documents a declaration (or, in a script, a top-level statement), and `ReferenceIds` to find reference
  ids, which it also looks for in the markdown documents, the CI workflows and the XML resources. `KotlinLexer` is the
  one lexer behind all text-reading rules: it tells comments and literal text from code for `KotlinComments`, for
  `SourceReferences` (the fully qualified names the layer rules check) and for `WallClockAssertions`, which
  `BudgetTestTaggingTest` asks whether a test asserts on the wall clock; behind it, `MeasuredValues` and `Lambdas`
  follow a measurement through the code of a test and `TimeLimits` judges the limits of timeouts. `BuildOutput` is the
  one rule that tells build output from sources; the `.gitignore` follows it, and `OwnCode` leaves build output and
  the checkouts of git submodules out of every rule. The tests read the submodule directories that the build hands
  them with build-logic's own `GitSubmodules`, which they compile as well.

Typical changes and where they start:

- a new editor action: an action class in `model.action` of ui-editor, listed in `defaultEditorActions`, with its id
  in `EditorActionIds`, its title in `EditorActionTitles` and its shortcut in `defaultKeyBindings`; the workbench
  registers it as a command (`EditorCommands`), and `IdeMenus` places it in a menu;
- a new language feature: the provider in the language's package of core-lang, registered in its `LanguageFeatures`;
- a new descriptor key: a property of the typed `EnvironmentDescriptor` in core-document with its annotations; the
  schema is derived from it, and checks, highlighting, completion and hover follow the schema;
- a new module: `settings.gradle.kts`, a rule in `ModuleDependencyRules` and an entry in `KarpfenModules`;
- a new runtime endpoint: the route on the runtime's branch with IDE support, an entry in `RuntimeRoute` with its
  texts in `RuntimeText`, a function of `RuntimeApi` (and of `RuntimeEnvironment` with its verdict in `LifecycleRules`
  where it concerns an environment), the fake's default answer if it needs one, the goldens, the anchors of
  `RuntimeSourceAnchorTest`, a row of `RuntimeContract`, and a live run;
- moving the karpfen-runtime submodule: the anchors and the route completeness fail exactly where the runtime's facts
  changed; the catalogue, the goldens and the hardcoded responses of the tests change together, the live suite runs,
  and then the anchors follow, as the KDoc of `RuntimeSourceAnchorTest` describes.

## Conventions

### Design

- Object-oriented and modular: every class has one responsibility (none much beyond 300 lines; coordinators delegate
  to collaborators), seams are interfaces with factory functions, collaborators are passed to constructors, and there
  are no stateful singletons. The application is wired by hand in the composition root, without a framework.
- Values that cross threads are immutable; mutable state stays on one thread, which for texts and editor state is the
  UI thread.
- Flows instead of listeners: state as `StateFlow`, events as flows, work in structured coroutine scopes.
- Composables contain no logic; they render state held by plain-Kotlin state holders and forward intents to them.
- Packages are organized by feature and form no cycles.
- Foreign APIs stay behind adapters: ANTLR and dsl-tools in core-document's `antlr` and `dsl` packages, the desktop's
  file operations and folder chooser in the workbench's `files.desktop` package, and logback in the `logging` packages
  of the application and the command-line tool.
- One source per fact: for example `KarpfenSyntax` for lexical facts, `EnvironmentDescriptor` for descriptor keys,
  `mergeDiagnostics` for merging diagnostics and `EditableText.mapRange` for mapping ranges between versions.

The architecture tests check many of these rules on every build.

### Documentation

- Documentation lives in KDoc block comments on declarations, in official KDoc syntax: a summary sentence first, then
  the details, `[Name]` links to types and members, and tags such as `@param` or `@property` only where they add
  information. In a Gradle script, KDoc may also document a top-level statement or block, such as `dependencies { }`,
  because a script has no other place for it; `KDocPlacement` documents the exact rule.
- There are no line comments, no other block comments and no comments between statements. When code needs an
  explanation, a well-named function or value carries it in its KDoc, or the KDoc of the enclosing declaration
  explains it.
- Technical detail (algorithms, invariants, threading, edge cases and the reasons for a design) lives in KDoc next to
  the code. Markdown documents such as this guide are a map and point to the code instead of repeating it.
- Documents are written for a human reader: they name other documents, sections and decisions in words, with a
  descriptive link where it helps, never by an id or a section number.
- Every Kotlin source file and Gradle script starts with the Apache 2.0 license header that the sources of
  karpfen-dsl-tools carry too; it is the one KDoc that documents no declaration, and `LicenseHeader` in the
  architecture tests holds it.

`DocumentationStyleTest` checks these rules on every build: the comments, where KDoc stands, and reference ids in
Kotlin sources and Gradle scripts, and reference ids in the markdown documents, the CI workflows and the XML
resources. The [decision that documentation lives in KDoc](adr/0012-documentation-lives-in-kdoc.md) records the
style.

### Testing

- Tests use JUnit 6 (Jupiter) with kotlin-test. Compose UI tests run headless with `runComposeUiTest`, and coroutine
  code runs with `runTest`. Time-dependent behaviour is tested on virtual time, not on the wall clock: debouncing with
  a `StandardTestDispatcher`, undo coalescing with a `TestTimeSource`, and the runtime client and sessions with every
  dispatcher and time source of their options on the test's scheduler.
- The core, runtime and UI modules each have a contract test of their public values and wiring, for example
  `DocumentContractTest`, `LanguageContractTest`, `RuntimeProtocolContractTest`, `RuntimeSessionContractTest`,
  `EditorContractTest` and `WorkbenchContractTest`.
- Shared fixtures are in the test fixtures of core-document: `Examples` loads the example files copied from the karpfen
  repositories, `DescriptorExamples` the environment descriptors written for the IDE's tests (`DescriptorFixture` of
  core-lang puts them into the example workspace), `SyntheticKstates` generates large KStates documents for the
  benchmarks, and `Mutations` generates the deterministic random mutations that the robustness tests (for example
  `TokenizerRobustnessTest`) feed through tokenizers, parsers and language services. core-workspace's test fixtures
  open workspaces on virtual time (`TestWorkspaces`, `FakeFileWatcher`, `settle` and `openDocument`), and the runtime
  modules' fixtures hold the fake runtime, the goldens, the scenario types and the synthesized models described in the
  section on the runtime client.
- Tests against a real runtime carry `@LiveRuntime`, the JUnit tag `live-runtime`, which every test task excludes
  except the `liveRuntimeTest` task of the runtime modules; unlike the tag of time-budget tests, it has a switch on
  purpose, the script that starts a runtime. Their waits are eventual with the generous bounds of `Patience`, and absence is proven
  by a cause, never by a time window.
- The end-to-end tests run headless in the `e2e` test packages of three modules. In app/ide, `IdeEndToEndTest` and
  `KeyRoutingEndToEndTest` run the whole application wiring. In ui-workbench, `WorkbenchEndToEndTest` runs the
  workbench over its production wiring, and `DescriptorEditingEndToEndTest` edits an environment descriptor as a user
  does. In ui-editor, `EditorWithPopupsTest`, `HiddenCompletionTest` and `HoverTooltipPointerTest` run an editor with
  its popups.
- Time-budget tests, which compare a wall-clock duration with a limit, carry `@Tag("budget")` and are excluded from
  every test run; `TestTags.BUDGET` explains why, `BudgetTestTaggingTest` catches a missing tag, and the
  [decision that time-budget tests are not run](adr/0011-time-budget-tests-are-not-run.md) records it. A wall-clock
  assertion never goes into a test without that tag.
- The [manual smoke test](manual-test.md) covers what headless tests cannot: the native menu bar, real keyboard
  layouts, focus and mouse handling, the file watcher and quitting.

## Where decisions and plans are recorded

- The [architecture decision records](adr/README.md) hold one record per decision, with an index.
- The [architecture proposal](architecture-proposal.md) is the accepted design record that the decisions came from.
- The [plan of the increments after phase 1](plans/increments-after-phase-1.md) describes the current work.
- The [phase-1 records](history/phase-1/README.md) keep the specification, work packages and status report of phase 1
  for reference.
