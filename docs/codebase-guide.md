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
architecture tests lists the prefix of every module.

## The big picture

karpfen IDE is a Gradle build of Kotlin/JVM modules in layers, and dependencies only point downwards:

- **core** is the language core and knows nothing about a UI. core-text holds the text of a document, core-document
  turns text into tokens, parse results and diagnostics on top of karpfen-dsl-tools (and, for environment descriptors,
  on top of its own JSON parser), core-workspace manages an opened folder with its modeling contexts and their
  analysis, and core-lang answers editor questions such as completion, hover and go to definition.
- **ui** is built with Compose Multiplatform for Desktop. ui-foundation holds the theme, widgets, commands and the key
  model, ui-editor the code editor, and ui-workbench the window content: project tree, editor tabs, outline, problems
  and status bar.
- **app/ide** is the desktop application and its composition root.
- **tools/cli** is the `karpfen` command-line tool. It validates a folder with the same workspace code as the IDE.
- **runtime**, **notation**, **bridges** and ui-diagram belong to later increments and are stubs so far.

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
  (`OutlineNode`), declarations and references (`SyntaxIndex`), folding regions and semantic spans.
- **ANTLR adapter.** The `antlr` package is the only code that touches ANTLR and the parsers generated in
  karpfen-dsl-tools. Its `lex` sub-package holds the tokenizers (`KstatesTokenizer`, and `GapRecoveringTokenizer` for
  KMeta and KModel), its `tree` sub-package the parse-tree walkers (`TreeParser`, `KstatesSyntaxAnalyzer` and their
  KMeta and KModel counterparts). `KarpfenLexers` creates the lexers, and `EvalBodies` finds the embedded Python of
  `EVAL` bodies.
- **dsl-tools adapter.** The `dsl` package wraps the dsl-tools converters (`DslConverter`) and places their errors,
  which carry no positions, in the text (`DslErrorLocator`).
- **JSON.** The `json` package reads JSON the way the editor needs it, written for the IDE rather than taken from a
  library: `JsonLexer` and `JsonTokenizer` split it into tokens, `JsonParser` builds a `JsonDocument` whose `JsonNode`
  tree keeps every range and survives broken text (`JsonTreeBuilder` documents how it recovers).
- **Environment descriptors.** The `environment` package is the descriptor language. The typed
  `EnvironmentDescriptor` is the one declaration of the descriptor's shape, and `EnvironmentSchema` derives the
  schema every other reader uses from it: the keys with their nesting and value shapes (`ValueShape`,
  `DescriptorKey`), which are required, and what role a named file plays (`DescriptorFileRole`). `shapedValues` walks a
  descriptor along the schema, and `EnvironmentSyntaxAnalyzer` turns a descriptor into outline, folding, semantic
  spans and syntax diagnostics. The `json` package holds the IDE's own JSON parser (`JsonParser`).
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
  files, and `FileKind.fits` which files fit the role of a key.
- **Modeling contexts.** The `context` package decides which files are analyzed together: `ModelingContext`, and
  `ContextResolver` with the rules for descriptors, directories and loose files. `ContextTracker` reads an open
  descriptor from its editor text, so its findings, the contexts and the analysis follow unsaved edits.
- **Analysis.** The `analysis` package converts every context with dsl-tools: `ProjectAnalyzer` documents the rules,
  `DefaultProjectAnalyzer` implements them, `ProjectAnalysis` is the result, and `AnalysisScheduler` runs the
  analysis again, debounced and cancellable, when the contexts or the open documents change. `ContextSymbols` answers
  what the analyzed files declare that descriptors refer to (objects, `ATTACHED TO` types, event domains); the
  descriptor's language services and the template of new descriptors both ask it.
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

## Workbench (ui-workbench)

Start at `WorkbenchState` in the `state` package: the mediator that owns the opened workspace and its language
services and coordinates the feature states. `DefaultWorkbenchState` lists the flows it delegates to, such as
`FileOpenFlow`, `SaveFlow` and `EditorLifecycle`. `WorkbenchServices` holds its collaborators;
`defaultWorkbenchServices` in the root package is the production wiring, and the `Workbench` composable lays out the
window.

Each feature has a package with a Compose-free state class and, where it draws something, a `view` sub-package with
its composables: `projecttree` (`ProjectTreeState`), `tabs` (`EditorTabsState` and the banner for changes on disk),
`outline` (`OutlineState`), `problems` (`ProblemsState`), `statusbar` (`StatusBarState`), `dialog` (`DialogState`) and
`layout` (`LayoutState`); `welcome` holds the view shown when no folder is open.

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
`menu` package resolves the menus against the registered commands. Logging is configured in
`app/ide/src/main/resources/logback.xml`.

Commands and keys come from ui-foundation: `CommandRegistry` (package `command`) holds the application's commands,
`KeyStroke`, `KeyShortcut` and `HostPlatform` (package `keys`) are the key model, and `keys.compose` converts Compose
key events. The editors and the menus take their shortcuts from the same `EditorKeymap`, so they always agree.

## Command-line tool (tools/cli)

`main` hands the arguments to `KarpfenCli`, which parses them with `CliArguments` and returns an exit status from
`ExitCodes`. `ValidateCommand` in the `validate` package opens a `Workspace` without watching, waits for the analysis
and reports `Workspace.problems`. `ValidationReport` and `ReportFormatter` turn the problems into text or JSON, and
`StandardOutputGuard` keeps what dsl-tools prints out of the report.

## Build, architecture rules and tests

- **Build logic.** `build-logic` holds the convention plugins `karpfen.kotlin-jvm` (applied by every module),
  `karpfen.kotlin-serialization`, `karpfen.compose` and `karpfen.application`, the module dependency matrix
  `ModuleDependencyRules` with its check `VerifyModuleDependencies`, and `TestTags`. Versions live in
  `gradle/libs.versions.toml`; `settings.gradle.kts` decides how karpfen-dsl-tools is resolved.
- **Architecture tests.** The `architecture-tests` module checks the layering with Konsist: `LayerImportRulesTest`
  (which libraries each layer may use), `ForeignApiConfinementTest` (ANTLR and dsl-tools only behind their adapters),
  `ComposeFreeStateTest` and `UiLayeringTest` (state holders without Compose, views on top), `PackageCycleTest`,
  `PackageLayoutTest`, `LexicalFactsTest` (one declaration per lexical fact) and `BudgetTestTaggingTest`.
  `KarpfenModules` is the module table these tests share. `DocumentationStyleTest` guards the documentation style in
  every Kotlin source file and Gradle script, with `KotlinComments` to find the comments, `KDocPlacement` to check
  that KDoc documents a declaration, and `ReferenceIds` to find reference ids, which it also looks for in the markdown
  documents, the CI workflows and the XML resources. `KotlinLexer` is the one lexer behind all text-reading rules: it
  tells comments and literal text from code for `KotlinComments`, for `SourceReferences` (the fully qualified names
  the layer rules check) and for `WallClockAssertions`, which `BudgetTestTaggingTest` asks whether a test asserts on
  the wall clock.

Typical changes and where they start:

- a new editor action: an action class in `model.action` of ui-editor, listed in `defaultEditorActions`, with its id
  in `EditorActionIds`, its title in `EditorActionTitles` and its shortcut in `defaultKeyBindings`; the workbench
  registers it as a command (`EditorCommands`), and `IdeMenus` places it in a menu;
- a new language feature: the provider in the language's package of core-lang, registered in its `LanguageFeatures`;
- a new descriptor key: a property of the typed `EnvironmentDescriptor` in core-document with its annotations; the
  schema is derived from it, and checks, highlighting, completion and hover follow the schema;
- a new module: `settings.gradle.kts`, a rule in `ModuleDependencyRules` and an entry in `KarpfenModules`.

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
  file operations and folder chooser in the workbench's `files.desktop` package.
- One source per fact: for example `KarpfenSyntax` for lexical facts, `EnvironmentDescriptor` for descriptor keys,
  `mergeDiagnostics` for merging diagnostics and `EditableText.mapRange` for mapping ranges between versions.

The architecture tests check many of these rules on every build.

### Documentation

- Documentation lives in KDoc block comments on declarations, in official KDoc syntax: a summary sentence first, then
  the details, `[Name]` links to types and members, and tags such as `@param` or `@property` only where they add
  information.
- There are no line comments, no other block comments and no comments between statements. When code needs an
  explanation, a well-named function or value carries it in its KDoc, or the KDoc of the enclosing declaration
  explains it.
- Technical detail (algorithms, invariants, threading, edge cases and the reasons for a design) lives in KDoc next to
  the code. Markdown documents such as this guide are a map and point to the code instead of repeating it.
- Documents are written for a human reader: they name other documents, sections and decisions in words, with a
  descriptive link where it helps, never by an id or a section number.

`DocumentationStyleTest` checks these rules on every build: the comments, where KDoc stands, and reference ids in
Kotlin sources and Gradle scripts, and reference ids in the markdown documents, the CI workflows and the XML
resources. The [decision that documentation lives in KDoc](adr/0012-documentation-lives-in-kdoc.md) records the
style.

### Testing

- Tests use JUnit 5 with kotlin-test. Compose UI tests run headless with `runComposeUiTest`, and coroutine code runs
  with `runTest`. Time-dependent behaviour is tested on virtual time, not on the wall clock: debouncing with a
  `StandardTestDispatcher`, undo coalescing with a `TestTimeSource`.
- The core and UI modules each have a contract test of their public values and wiring, for example
  `DocumentContractTest`, `LanguageContractTest`, `EditorContractTest` and `WorkbenchContractTest`.
- Shared fixtures are in the test fixtures of core-document: `Examples` loads the example files copied from the karpfen
  repositories, `DescriptorExamples` the environment descriptors written for the IDE's tests (`DescriptorFixture` of
  core-lang puts them into the example workspace), `SyntheticKstates` generates large KStates documents for the
  benchmarks, and `Mutations` generates the deterministic random mutations that the robustness tests (for example
  `TokenizerRobustnessTest`) feed through tokenizers, parsers and language services.
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
