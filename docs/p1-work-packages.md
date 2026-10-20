# P1 — Work packages

Status: v1.0, 2026-10-20 (contracts step). Companion to [p1-text-editing-spec.md](p1-text-editing-spec.md) (the
contract of phase 1; §1a, the design principles, is a hard requirement). This document assigns every file created in
the contracts step to exactly one work package (WP), says what each WP implements, which fakes it may use while its
dependencies are not merged, which spec sections and acceptance tests it must satisfy, and what it must not touch.

## 1. Ownership model

The contracts step created compiling skeletons of every module. Each file is in one of two classes:

- **CONTRACT files** contain complete value objects, interfaces, factories and wiring. Nobody edits them without the
  [contract change protocol](#5-contract-change-protocol). They are listed in [§3](#3-contract-files-frozen).
- **Owned files** contain `TODO("WP-<ID>: …")` bodies. Exactly one WP owns each of them (the id in the TODO). The owner
  implements the bodies, may add private/internal members, may restructure internals and may add new files **in the
  packages it owns**. Public (and the listed internal) signatures in owned files are part of the contract: keep them.

Package ownership: a WP owns the packages listed under "Owns" below, including their test packages
(`src/test/kotlin/<same package path>`). New files go into owned packages only. A WP may add public API to its *own*
module (new types, new functions, new members with default implementations); it never changes another WP's files.

Notation: paths are relative to the repository root; `…/` abbreviates `src/main/kotlin/io/karpfen/ide/` of the module
in the heading.

## 2. Rules for every work package

1. Branch/worktree per WP; commits end with the trailer line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
   Never run Docker or the karpfen runtime; never modify karpfen-dsl-tools or karpfen-runtime.
2. `JAVA_HOME=<JDK 21> ./gradlew build` is green at every merge, in artifact mode (composite mode is checked by CI).
   That includes `verifyModuleDependencies` and `architecture-tests` (ANTLR/converter confinement, Compose-free models
   and state holders, no package cycles in core modules, P0 layer rules).
3. No `TODO(` remains in owned files at merge time.
4. **No test executes code of a WP that is not merged yet.** Use the fakes listed per WP (or write your own in your test
   sources). Every interface at a seam exists so it can be faked.
5. Spec §1 and §1a apply everywhere: one responsibility per class (≤ ~300 lines), constructor injection, no stateful
   `object`s, flows instead of listeners, immutable values across threads, time via `kotlin.time.TimeSource` /
   coroutine virtual time, dispatchers and file-system roots injected, SLF4J only (no `println` except CLI output),
   explicit API + KDoc on every public declaration in `core/*`, KDoc on public UI API as well.
6. Randomized tests use fixed seeds. Budgets are asserted with 5x slack and the measured value is printed through the
   test log (SLF4J or test reporter), as the spec requires.
7. Build files: a WP may only touch its own module's `build.gradle.kts`, and only to add **test-only** libraries
   (`testImplementation`/`testRuntimeOnly`). All production dependencies are in place. WPs sharing a module
   (TOKENS + PARSE in core-document; EDITOR-MODEL + EDITOR-VIEW + EDITOR-POPUPS in ui-editor) add lines only; the later
   merge resolves the trivial conflict.
8. Shared test fixtures (`core/core-document/src/testFixtures`) are CONTRACT. Use them through
   `testImplementation(testFixtures(project(":core:core-document")))` (already declared in every module that needs
   them): `Examples.files()`, `Examples.contexts()`, `Examples.copyTo(dir)`, `Mutations.random(text, seed, count)`,
   `Mutations.prefixes(text)`, `SyntheticKstates(n)` / `SyntheticKstates.withMinLines(n)`.
9. The phase-0 `<Name>Module` stub objects and their tests stay untouched.

### Merge order

| Wave | Work packages (in parallel) | Requires merged |
|---|---|---|
| 1 | WP-TEXT, WP-FOUNDATION | — |
| 2 | WP-TOKENS, WP-PARSE, WP-EDITOR-MODEL | WP-TEXT |
| 3 | WP-WORKSPACE, WP-LANG | WP-TOKENS, WP-PARSE |
| 3 | WP-EDITOR-VIEW, WP-EDITOR-POPUPS | WP-FOUNDATION, WP-EDITOR-MODEL, WP-TOKENS, WP-PARSE |
| 4 | WP-APP | WP-WORKSPACE, WP-LANG |
| 4 | WP-WORKBENCH | WP-WORKSPACE, WP-LANG, WP-EDITOR-VIEW, WP-EDITOR-POPUPS |
| 5 | INTEGRATION | all |

WP-APP compiles against the workbench API (CONTRACT) but cannot run the application before WP-WORKBENCH is merged;
the launch smoke test (spec §11 item 6) is verified by INTEGRATION.

## 3. Contract files (frozen)

Changes only through [§5](#5-contract-change-protocol). Owners of the surrounding module may *read* and *use* them.

core/core-text (`…/core/text/`): `TextRange.kt`, `TextPosition.kt`, `TextEdit.kt`, `Bias.kt`, `CaretMemento.kt`,
`EditOrigin.kt`, `TextChange.kt`, `UndoResult.kt`, `TextSnapshot.kt`, `UndoManager.kt`, `EditableText.kt`.

core/core-document (`…/core/document/`): `KarpfenLanguage.kt`, `Diagnostic.kt`, `syntax/TokenKind.kt`,
`syntax/SyntaxToken.kt`, `syntax/TokenizeResult.kt`, `syntax/Tokenizer.kt`, `parse/SymbolKind.kt`,
`parse/SemanticSpan.kt`, `parse/OutlineNode.kt`, `parse/Symbols.kt`, `parse/SyntaxIndex.kt`, `parse/DslModel.kt`,
`parse/ParseOutcome.kt`, `parse/DocumentParser.kt`, `language/LanguageSupport.kt`, `language/LanguageRegistry.kt`,
`registry/DefaultLanguageRegistry.kt`, `registry/KarpfenLanguageSupports.kt`, `antlr/CodePointOffsets.kt` (shared by
TOKENS and PARSE, complete, tested), `dsl/DslConverter.kt`, `dsl/DslErrorLocator.kt`, `session/DocumentId.kt`,
`session/DocumentSession.kt`, `session/DocumentSessionConfig.kt`, `session/VersionedDiagnostics.kt`; the test
fixtures `src/testFixtures/**` and the tests `src/test/kotlin/io/karpfen/ide/core/document/fixtures/ExamplesFixtureTest.kt`,
`…/dsl/DslToolsSmokeTest.kt`, `…/dsl/FixtureConversionTest.kt`, `…/antlr/CodePointOffsetsTest.kt`.

core/core-workspace (`…/core/workspace/`): `Workspace.kt`, `WorkspaceOptions.kt`, `files/FileKind.kt`,
`files/WorkspaceTree.kt`, `files/FileWatcher.kt`, `document/DocumentManager.kt`, `document/OpenDocument.kt`,
`document/TextFileStore.kt`, `environment/EnvironmentDescriptor.kt`, `environment/EnvironmentDescriptorReader.kt`,
`context/ModelingContext.kt`, `context/ContextResolution.kt`, `context/ContextResolver.kt`,
`analysis/ProjectAnalysis.kt`, `analysis/ProjectAnalyzer.kt`.

core/core-lang (`…/core/lang/`): `LanguageServices.kt`, `LanguageDocument.kt`, `Highlighting.kt`, `Completion.kt`, and
the *signatures* of `defaultLanguageServices(…)` and `karpfenLanguageFeatures()` in
`services/LanguageServicesFactory.kt` (the file itself is owned by WP-LANG, see below).

ui/ui-foundation (`…/ui/foundation/`): `keys/KeyStroke.kt`, `command/Command.kt`.

ui/ui-editor (`…/ui/editor/`): `model/caret/Caret.kt`, `model/action/EditorAction.kt`,
`model/action/EditorActionIds.kt`, `model/navigation/GoToLineQuery.kt`, `state/CodeEditorState.kt`,
`state/EditorOverlayController.kt` (complete; shared by VIEW and POPUPS), `overlay/EditorOverlays.kt` (the slot API
between VIEW and POPUPS).

ui/ui-workbench (`…/ui/workbench/`): `state/WorkbenchState.kt`, `state/WorkbenchServices.kt`,
`command/WorkbenchCommandIds.kt`, `settings/IdeSettings.kt`, `settings/SettingsRepository.kt`, and the *signature* of
`defaultWorkbenchServices(…)` in `DefaultWorkbenchServices.kt` and of `Workbench(…)` in `Workbench.kt`.

tools/cli (`…/tools/cli/`): `validate/ValidationReport.kt`.

architecture-tests: `KarpfenModules.kt`, `ForeignApiConfinementTest.kt`, `ComposeFreeStateTest.kt`,
`PackageCycleTest.kt` and the P0 tests.

Contract tests (they pin the complete values and wiring; extend coverage in your own test files, do not edit these):
`core/core-text/src/test/kotlin/io/karpfen/ide/core/text/TextValuesContractTest.kt`,
`core/core-document/src/test/kotlin/io/karpfen/ide/core/document/DocumentContractTest.kt`,
`core/core-workspace/src/test/kotlin/io/karpfen/ide/core/workspace/WorkspaceContractTest.kt`,
`ui/ui-foundation/src/test/kotlin/io/karpfen/ide/ui/foundation/keys/KeysContractTest.kt`,
`ui/ui-editor/src/test/kotlin/io/karpfen/ide/ui/editor/EditorContractTest.kt`,
`ui/ui-workbench/src/test/kotlin/io/karpfen/ide/ui/workbench/WorkbenchContractTest.kt`.

Build files: all `build.gradle.kts` (except test-only additions, rule 7), `gradle/libs.versions.toml`, `build-logic/**`.

## 4. Work packages

### WP-TEXT — core-text

- **Owns**: package `io.karpfen.ide.core.text` (main and test) except the CONTRACT files. Skeletons
  (`core/core-text/…/core/text/`): `DefaultEditableText.kt`, `DefaultUndoManager.kt`, `LineIndex.kt` (bodies),
  `LineSeparator.kt` (the `LineSeparators` bodies; `LineSeparator` and `NormalizedText` are complete). New internal
  files (edit log, offset mapping, coalescing policy) go into the same package.
- **Implements**: `EditableText` (via `DefaultEditableText`), `UndoManager`, `LineIndex`, `LineSeparators`.
- **Fakes**: none needed (no dependencies). Time: `kotlin.time.TestTimeSource`.
- **Must satisfy**: spec §1 (offsets, separators, threading, determinism), §2 all MUST rules. Acceptance tests (spec §2
  Tests): ≥ 10,000 random ops per seed against a `StringBuilder` reference; undo-all returns the initial text,
  redo-all the final one; coalescing rules (typing, deletions, newline/whitespace breaks, 1000 ms window,
  Paste/Cut/Command/External never coalesce, `group`); `isAtSavePoint` through undo/redo; `mapOffset` with both
  biases against a reference and `null` after eviction; `LineIndex` edge cases (empty text, trailing newline, only
  newlines, very long line); `LineSeparators` round trip and mixed detection; `apply` rejects overlapping edits and
  returns null for no-ops.
- **Must not touch**: any other module.

### WP-FOUNDATION — ui-foundation

- **Owns**: all of `io.karpfen.ide.ui.foundation` except CONTRACT files. Skeletons (`ui/ui-foundation/…/ui/foundation/`):
  `keys/ComposeKeys.kt`, `command/CommandRegistry.kt`, `theme/KarpfenTheme.kt`, `icons/KarpfenIcons.kt`,
  `layout/SplitPane.kt` (`SplitPane`, `ToolWindow`), `widgets/TabStrip.kt`, `widgets/TreeView.kt`, `widgets/StatusBar.kt`.
- **Implements**: `CommandRegistry` (`DefaultCommandRegistry`), `KeyEvent.toKeyStroke()`, `KeyShortcut.displayText`,
  `KeyShortcut.toMenuShortcut`, palettes/typography, `KarpfenTheme`, icons (`ImageVector.Builder`, no
  material-icons-extended), the widgets.
- **Fakes**: none needed.
- **Must satisfy**: spec §6. Tests: registry register/replace/unregister/lookup by stroke per platform, disabled
  commands do not execute; shortcut display per platform; key-event mapping (headless `runComposeUiTest` with key
  input); headless UI tests for SplitPane drag + min sizes, TabStrip select/close/middle-click/dirty dot/overflow
  scroll, TreeView expand/collapse/selection/keyboard Up/Down/Left/Right/Enter/double-click, StatusBar items.
- **Must not touch**: other modules; `keys/KeyStroke.kt`, `command/Command.kt`.

### WP-TOKENS — core-document: tokenizers and document session

- **Owns**: packages `io.karpfen.ide.core.document.antlr.lex` and `io.karpfen.ide.core.document.session` (except the
  CONTRACT files there). Skeletons (`core/core-document/…/core/document/`): `antlr/lex/KmetaTokenizer.kt`,
  `antlr/lex/KmodelTokenizer.kt`, `antlr/lex/KstatesTokenizer.kt`, `session/DefaultDocumentSession.kt`. Tests in
  `src/test/kotlin/io/karpfen/ide/core/document/antlr/lex/` and `…/session/`.
- **Implements**: `Tokenizer` ×3 (ANTLR lexers with the console listener removed and an IDE collector; comment
  recovery for KMeta/KModel; HIDDEN-channel comments for KStates; EVAL body scanning with `$( … )`, `@name`, Python
  keywords/strings/numbers/`#` comments, `//` → COMMENT + `eval-double-slash` WARNING; `EVENT` as OPERATION in action
  blocks), `DocumentSession` (tokens via mapLatest without debounce, initial tokens synchronous; debounced, cancellable
  parse with the initial parse undebounced; `lastGoodParse`; merged, deduplicated, sorted `VersionedDiagnostics`
  mapped to the newest version; `setExternalDiagnostics`; `close`). Use `CodePointOffsets` for every ANTLR index.
  Keep the no-arg constructors (used by `registry/KarpfenLanguageSupports.kt`) and the factory semantics.
- **Fakes**: `DocumentParser` is WP-PARSE: build sessions with `LanguageSupport(language, realTokenizer, fakeParser)`
  where the fake returns hand-built `ParseOutcome`s (also with syntax diagnostics, to test `lastGoodParse`).
  Time: `StandardTestDispatcher` in `DocumentSessionConfig` + `runTest` virtual time.
- **Must satisfy**: spec §3.1 (all MUST rules), §3.2, §3.4 (flows, debounce, merging) and the tokenize budget
  (5,000-line KStates < 30 ms, asserted with 5x slack, measured value reported; use `SyntheticKstates.withMinLines(5000)`),
  §11 item 2 (zero LEXER diagnostics for every example) and item 3 for tokenizers (never throw on
  `Mutations.random(…, count = 10_000)` over the examples and on every prefix of one KStates example; token invariants
  hold on all of them). Rule from §3.1: every non-whitespace character in exactly one token — test it on all mutations.
- **Must not touch**: `antlr/tree`, `dsl`, `parse`, `registry`, `language`, the root package, `antlr/CodePointOffsets.kt`,
  fixtures.

### WP-PARSE — core-document: ANTLR parse adapter, tree walkers, dsl-tools adapter

- **Owns**: packages `io.karpfen.ide.core.document.antlr.tree` and `io.karpfen.ide.core.document.dsl` (except the
  CONTRACT files `dsl/DslConverter.kt`, `dsl/DslErrorLocator.kt`), and the owned file `parse/ParserSeams.kt`.
  Skeletons (`core/core-document/…/core/document/`): `antlr/tree/KmetaSyntaxAnalyzer.kt`,
  `antlr/tree/KmodelSyntaxAnalyzer.kt`, `antlr/tree/KstatesSyntaxAnalyzer.kt`, `dsl/DefaultDslConverter.kt`,
  `dsl/QuotedNameErrorLocator.kt`, `dsl/StandaloneSemanticsAdapters.kt`, `parse/ParserSeams.kt`
  (`ComposedDocumentParser` and the internal seams `SyntaxAnalyzer`, `SyntaxAnalysis`, `StandaloneSemantics`,
  `StandaloneResult`, which WP-PARSE may refine). Tests in `…/core/document/antlr/tree/`, `…/dsl/` (new files only),
  `…/parse/`.
- **Implements**: `SyntaxAnalyzer` ×3 (parse with IDE error listeners on lexer and parser, default recovery; walkers for
  outline, `SyntaxIndex`, folding, semantic spans; tolerate missing tokens and error nodes; name ranges inside quotes;
  every extraction listed in spec §3.3), `ComposedDocumentParser` (syntax, then standalone semantics only for
  `SemanticMode.STANDALONE` and clean syntax; collaborator exceptions → `internal-error`), `DslConverter`
  (`DefaultDslConverter`: the three converters, exceptions → DSL_TOOLS ERROR diagnostics), `DslErrorLocator`
  (`QuotedNameErrorLocator`), `KmetaStandaloneSemantics`, `KstatesStandaloneSemantics`. Keep the constructors used by
  `registry/KarpfenLanguageSupports.kt` (`KmetaSyntaxAnalyzer()`, …, `KmetaStandaloneSemantics(converter)`,
  `ComposedDocumentParser(language, syntax, semantics)`); add parameters only with defaults.
- **Fakes**: none needed beyond core-text (merged). The tokenizer is not needed (first token for error location is
  derived from the text).
- **Must satisfy**: spec §0 (ANTLR only via the adapter), §3.3 (all MUST rules), the parse budget (5,000-line KStates
  < 150 ms, 5x slack, reported), §11 item 2 (zero PARSER diagnostics on every example; standalone conversion succeeds
  for every example `.kmeta` except `EVENTS.kmeta`, which is parsed with `SYNTAX_ONLY`, and every `.kstates`), item 3 for
  parser/outline/index (never throw on 10,000 mutations and all prefixes; `OutlineNode`/`SymbolDeclaration` invariants
  hold, i.e. clamp ranges of conjured tokens). Error location tests: a message naming `'Vector'` points at the quoted
  name; unknown messages point at the first token.
- **Must not touch**: `antlr/lex`, `session`, `syntax`, `language`, `registry`, the root package, the CONTRACT files in
  `parse`, `antlr/CodePointOffsets.kt`, the existing tests in `…/dsl/`, fixtures.

### WP-EDITOR-MODEL — ui-editor, Compose-free model

- **Owns**: packages `io.karpfen.ide.ui.editor.model.caret`, `.model.action`, `.model.find`, `.model.completion`,
  `.model.navigation` except CONTRACT files. Skeletons (`ui/ui-editor/…/ui/editor/model/`): `caret/CaretModel.kt`,
  `action/EditorActionRegistry.kt` (registry and keymap), `action/DefaultEditorActions.kt` (wiring; keep the function
  signature), `action/NavigationActions.kt`, `action/TypingActions.kt`, `action/LineActions.kt`,
  `action/ClipboardActions.kt`, `find/FindModel.kt`, `completion/CompletionSessionModel.kt`.
- **Implements**: `CaretModel` (movement by char/word/line/page/document, smart Home, selection extension, select
  word/line/all, version-stamped carets mapped through foreign changes), every action of `EditorActionIds` (one undo
  step each, caret mementos before/after), `typeTextAction`, `EditorActionRegistry`, `defaultEditorKeymap` (bindings
  listed on `EditorKeymap`), `FindModel`, `CompletionSessionModel` (triggers, filtering and ranking, accept as one undo
  step, close rules, `handleKey`).
- **Fakes**: `EditorActionContext`, `ClipboardPort`, `EditorUiPort`, `CompletionSource` (hand-written fakes); tokens as
  hand-built `TokenizeResult`s (e.g. EMBEDDED_* tokens to mark an EVAL body for toggle comment); `KeyStroke`s built
  directly (values are complete). Real `EditableText` (WP-TEXT merged). Coroutines: `UnconfinedTestDispatcher`.
- **Must satisfy**: spec §7.1 (all bullets), "every action is exactly one undo step", undo/redo restore carets.
  Tests per action group, per movement, per find option (invalid regex → error state), completion ranking/stability.
- **Must not touch**: `state`, `view`, `theme`, `overlay`, `popup`; CONTRACT files.

### WP-WORKSPACE — core-workspace

- **Owns**: all of `io.karpfen.ide.core.workspace` except CONTRACT files. Skeletons
  (`core/core-workspace/…/core/workspace/`): `DefaultWorkspace.kt`, `files/WatchServiceFileWatcher.kt`,
  `files/WorkspaceScanner.kt`, `document/DefaultDocumentManager.kt`, `document/AtomicTextFileStore.kt`,
  `environment/JsonEnvironmentDescriptorReader.kt`, `context/DefaultContextResolver.kt`,
  `analysis/DefaultProjectAnalyzer.kt`.
- **Implements**: `Workspace` (scan, watch + 200 ms debounce, refresh, contexts, debounced 300 ms cancellable analysis,
  `isAnalyzing`, `awaitAnalysis`, pushing `"project"` diagnostics into open sessions), `FileWatcher` (recursive
  `WatchService`), `DocumentManager`/`OpenDocument` (UTF-8 with replacement + WARNING, line separators + INFO as
  external source `"file"`, dirty flag from the save point, atomic save restoring separators, self-induced event
  suppression, reload as one `External` change when clean, conflict when dirty or deleted, `SYNTAX_ONLY` sessions for
  `EVENTS.kmeta`), `TextFileStore`, `EnvironmentDescriptorReader` (unknown keys → WARNING, JSON value ranges),
  `ContextResolver` (rules on the interface), `ProjectAnalyzer` (through `DslConverter` only; never calls dsl-tools
  directly — enforced by architecture tests).
- **Fakes**: a `FileWatcher` fake that emits `FileChange`s on demand (the JDK's macOS watch service polls slowly; at most
  one real-watcher test, tagged slow-tolerant with generous timeouts); optionally an in-memory `TextFileStore`.
  Real tokenizers/parsers/`DslConverter` (WP-TOKENS, WP-PARSE merged). Virtual time via test dispatchers in
  `WorkspaceOptions`. Real folders via `Examples.copyTo(@TempDir)`.
- **Must satisfy**: spec §4 (all bullets) and §11 item 2 (every example context analyzes with zero DSL_TOOLS errors).
  Tests: tree order and ignore rules; FileKind; descriptor reading (valid, invalid JSON, unknown key, missing file with
  range); implicit contexts incl. several metamodels; analysis of every example context; external-change reload and
  conflict; save round trip with CRLF/CR files; malformed UTF-8.
- **Must not touch**: other modules; CONTRACT files.

### WP-LANG — core-lang

- **Owns**: all of `io.karpfen.ide.core.lang` except the CONTRACT root files (`LanguageServices.kt`,
  `LanguageDocument.kt`, `Highlighting.kt`, `Completion.kt`). Skeletons (`core/core-lang/…/core/lang/`):
  `feature/LanguageFeatures.kt` (SPI, may be extended), `context/CaretContext.kt`, `modelpath/ModelPathResolver.kt`,
  `highlight/DefaultHighlightProvider.kt`, `brackets/TokenBracketMatcher.kt`, `kmeta/Kmeta{LanguageFeatures,
  CompletionProvider,NavigationProvider,HoverProvider}.kt`, the same four files in `kmodel/` (`Kmodel…`) and `kstates/`
  (`Kstates…`), `services/DefaultLanguageServices.kt`, `services/LanguageServicesFactory.kt` (keep the two public
  signatures). The shared mechanics (`CaretContextDetector`, `ModelPathResolver`) and their value types may be refined.
- **Implements**: `LanguageServices` facade (delegation per language, never throws, logs provider failures),
  highlighting (lexical + semantic overlay when versions match), per-language completion/navigation/hover per spec §5,
  bracket matching, caret-context detection and model-path resolution as shared collaborators.
- **Fakes**: none for code; project knowledge is built directly: `ProjectAnalysis`/`ContextAnalysis`/`AnalyzedFile`/
  `ModelingContext` are complete values, metamodels/models/state machines come from `DslConverter()` (WP-PARSE) over
  `Examples.contexts()`, parse outcomes from `defaultLanguageRegistry()`. Pass `MutableStateFlow(analysis)` to
  `defaultLanguageServices`. Do not use WP-WORKSPACE's analyzer.
- **Must satisfy**: spec §5 (completion by context for all three languages, definition/references, hover, matching
  bracket, structure with per-language strategies and shared collaborators) and §11 item 3 for highlight, completion,
  definition and hover (never throw on 10,000 mutations of the examples and every prefix of one KStates example).
- **Must not touch**: other modules; the CONTRACT root files.

### WP-EDITOR-VIEW — ui-editor view, state, theme

- **Owns**: packages `io.karpfen.ide.ui.editor.state` (except CONTRACT files `CodeEditorState.kt`,
  `EditorOverlayController.kt`), `io.karpfen.ide.ui.editor.view`, `io.karpfen.ide.ui.editor.theme`. Skeletons
  (`ui/ui-editor/…/ui/editor/`): `state/DefaultCodeEditorState.kt`, `state/EditorViewport.kt`,
  `view/CodeEditor.kt`, `view/EditorStateFactories.kt` (`rememberCodeEditorState`, `systemClipboard`),
  `view/LineLayoutCache.kt`, `theme/EditorTheme.kt` (palettes and `LocalEditorTheme`; the data class is frozen).
- **Implements**: `CodeEditorState` (models wiring, `EditorActionContext`/`EditorUiPort`/`CompletionSource`
  implementations — completion must pass a `LanguageDocument` whose tokens belong to its text, tokenizing synchronously
  if the session's tokens lag — highlights and bracket match off the UI thread, hover content, scroll/navigation/focus
  requests), `EditorViewport`, `CodeEditor` (rendering, virtualization, layout cache, gutter, decorations, input incl.
  IME/dead keys/macOS Option combinations and `SemanticsActions.InsertTextAtCursor`, keymap routing with completion
  first, mouse, scrollbars, caret reveal with 3-line margin), hosting `EditorOverlays.Header`/`Popups` with an
  `EditorOverlayHost` implementation, editor themes, the system clipboard port.
- **Fakes**: a fake `LanguageServices` (fixed spans/items/locations); `EditorOverlays.None` or a test overlay that
  records host calls (POPUPS is developed in parallel). Real sessions via `DocumentSession(…)` with
  `defaultLanguageRegistry()` (TOKENS and PARSE merged), real models (EDITOR-MODEL merged), real foundation.
- **Must satisfy**: spec §7.2 (rendering, input, mouse, scrolling, `scrollTo`) and the headless benchmark (10,000-line
  KStates via `SyntheticKstates.withMinLines(10_000)`, scrolled to the middle, average frame time after warm-up
  < 50 ms, reported), §11 item 4 (headless Compose tests: typing incl. `performTextInput`, selection by mouse and keys,
  Cmd/Ctrl+click navigation callback, hover after 600 ms with diagnostics, scroll-to-caret).
- **Must not touch**: `model/**`, `popup/**`, `overlay/**`, CONTRACT files.

### WP-EDITOR-POPUPS — ui-editor popups

- **Owns**: package `io.karpfen.ide.ui.editor.popup`. Skeletons (`ui/ui-editor/…/ui/editor/popup/`): `EditorPopups.kt`,
  `CompletionPopup.kt`, `FindReplaceBar.kt`, `GoToLinePopup.kt`, `HoverTooltip.kt`, `EditorContextMenu.kt`.
- **Implements**: `EditorPopups : EditorOverlays` — `Header` (find/replace bar) and `Popups` (completion list, go-to-line,
  hover tooltip, context menu), each composable rendering immutable state and calling the host's models.
- **Fakes**: a fake `CodeEditorState` (interface) wired to real models (`CaretModel`, `FindModel`,
  `CompletionSessionModel` from EDITOR-MODEL) and a real `EditorOverlayController`; a fake `EditorOverlayHost` with
  fixed geometry; an `EditorTheme` built in the test (the palettes are WP-EDITOR-VIEW's). Do not call
  `CodeEditorState(…)`/`rememberCodeEditorState` (VIEW, not merged).
- **Must satisfy**: spec §7.2 Popups bullet: completion ≤ 12 rows with kind badge/label/detail; find bar with Aa/W/.*
  toggles, "n/m" counter, Enter/Shift+Enter, Escape returns focus (`requestEditorFocus`); go-to-line `line[:col]`;
  hover tooltip; context menu Cut/Copy/Paste/Go to Definition/Toggle Comment by action id. Headless tests per popup.
- **Must not touch**: everything outside `popup`.

### WP-WORKBENCH — ui-workbench

- **Owns**: all of `io.karpfen.ide.ui.workbench` except CONTRACT files. Skeletons (`ui/ui-workbench/…/ui/workbench/`):
  `Workbench.kt` (body), `DefaultWorkbenchServices.kt` (body), `state/DefaultWorkbenchState.kt`,
  `state/ProjectTreeState.kt`, `state/EditorTabsState.kt`, `state/OutlineState.kt`, `state/ProblemsState.kt`,
  `state/StatusBarState.kt`, `state/DialogState.kt` (sub-states may be refined freely; they are not consumed outside
  the module), `settings/JsonSettingsRepository.kt`, `templates/FileTemplates.kt`, `files/ProjectFileOperations.kt`,
  `projecttree/ProjectTreeView.kt`, `tabs/EditorArea.kt`, `outline/OutlineView.kt`, `problems/ProblemsView.kt`,
  `statusbar/WorkbenchStatusBar.kt`, `dialog/WorkbenchDialogs.kt`, `welcome/WelcomeView.kt`.
- **Implements**: `WorkbenchState` (Mediator), the sub-states, `Workbench` composable and views, `SettingsRepository`
  (JSON, corrupt file backup), templates, file operations and folder chooser, command registration
  (`WorkbenchCommandIds` + forwarding `EditorActionIds` to the active editor), light/dark theme provision.
- **Fakes**: fake `FolderChooser`, `ProjectFileOperations` (or the real one on `@TempDir`), `WorkbenchServices` with
  fakes where useful; real everything else (all dependencies merged). Settings directory: `@TempDir`.
- **Must satisfy**: spec §8 (all bullets) with unit tests per sub-state (Compose-free) and headless UI tests for the
  layout, tree open, tab close with unsaved changes, problems navigation, status bar; settings persistence and
  corrupt-file recovery.
- **Must not touch**: other modules; CONTRACT files.

### WP-APP — app/ide and tools/cli

- **Owns**: package `io.karpfen.ide.app` and `io.karpfen.ide.tools.cli` (incl. `validate`) except the CONTRACT file
  `validate/ValidationReport.kt`. Skeletons: `app/ide/…/app/IdeApplication.kt`, `app/ide/…/app/IdeMenus.kt`,
  `app/ide/…/app/CrashHandling.kt`, `app/ide/…/app/Main.kt` (rewrite: `main(args)`, optional folder argument,
  `KARPFEN_IDE_EXIT_AFTER_MS` kept), `tools/cli/…/tools/cli/Main.kt` (rewrite: `main(args)` → exit code; keep a one-line
  `USAGE`), `tools/cli/…/tools/cli/CliArguments.kt`, `tools/cli/…/tools/cli/validate/ValidateCommand.kt`,
  `…/validate/ReportFormatter.kt`, `…/validate/StandardOutputGuard.kt`; new resources `app/ide/src/main/resources/logback.xml`
  and `tools/cli/src/main/resources/logback.xml`.
- **Implements**: the composition root (`IdeApplication`), menus from `CommandRegistry` + `IdeMenus.specs`, native macOS
  menu bar, window title and bounds, close → `requestExit`, crash handling with a dialog, logging config (console WARN,
  rolling file `~/.karpfen-ide/logs/ide.log`); CLI argument parsing, `validate` (workspace without watching,
  `awaitAnalysis`, per-file tokenize/parse, stdout guarded), text/JSON formatters, exit codes (1 on ERROR).
- **Fakes**: app tests must not run `WorkbenchState`/`Workbench` (WP-WORKBENCH may not be merged): test `IdeMenus.specs`,
  `CrashHandling` with a recording callback, menu-bar building with a `CommandRegistry` fake if WP-FOUNDATION is not
  merged yet. CLI tests use the real workspace (WORKSPACE/LANG merged) over `Examples.copyTo(@TempDir)`.
- **Must satisfy**: spec §9 and §11 item 5 (`karpfen validate <examples dir>` exits 0 with no ERROR output; a broken copy
  exits 1 with `path:line:col: error: … [code]`, one-based; JSON format parses).
- **Must not touch**: other modules; `logback.xml` must not be added anywhere else.

### INTEGRATION — end-to-end tests and documentation

- **Owns**: new test packages `…/e2e/` in `app/ide`, `tools/cli` and `ui/ui-workbench` test sources;
  `docs/p1-manual-test.md` (new); `README.md` (status, module table, run instructions).
- **Implements**: end-to-end tests over the merged system: open the examples folder in a `WorkbenchState`, edit, see
  diagnostics and completion, save/undo, external change; DoD checks of spec §11 not already covered by a WP.
- **Must satisfy**: spec §11 items 1–7 (composite mode in CI; `KARPFEN_IDE_EXIT_AFTER_MS=6000 ./gradlew :app:ide:run
  --args=<examples dir>` exits 0 with no ERROR in the log; the 10-minute manual smoke test document for the owner).
- **Must not touch**: production code. Gaps found are reported through §5, not patched.

## 5. Contract change protocol

1. A WP may **add** to its own module's API (new types, new functions, new interface members **with default
   implementations**) when the addition is needed for its own work and breaks no other WP's fakes or callers.
2. A WP **never changes another WP's files** and never changes CONTRACT files, not even for "obvious" fixes.
3. A **contract gap** (a missing member, a wrong type, an invariant that cannot hold, a spec conflict) is **reported,
   not patched**: describe it in the WP's final report (file, symbol, problem, proposed change, who is affected) and
   work around it locally (e.g. an internal helper, a fake, a documented limitation) so the WP can still merge green.
4. The architect (or INTEGRATION, after all WPs) applies accepted contract changes in one commit that updates all
   affected callers and fakes and this document.
5. Changing a public signature in an owned file, removing a declaration, adding an abstract member to a consumed
   interface, or renaming a package always goes through steps 3–4.

## 6. Decisions of the contracts step that refine the spec

Where the spec sketch and the design principles (§1a) conflicted, the principles won:

1. Mutable components are interfaces with internal implementations and Kotlin factory functions named like the
   interface (`EditableText(…)`, `DocumentSession(…)`, `Workspace(…)`, `CaretModel(…)`, …) instead of classes.
2. `EditableText.apply` and `replaceAll` return `TextChange?` (null for no-ops, which produce no version).
3. Time is `kotlin.time.TimeSource` (tests: `TestTimeSource`); debounce uses injected dispatchers (virtual time).
4. `DocumentParser.parse(snapshot, mode: SemanticMode)`: `EVENTS.kmeta` sessions use `SYNTAX_ONLY`, because event
   payloads reference domain types and only convert with base types in core-workspace; a standalone conversion would
   report false DSL_TOOLS errors.
5. `ParseOutcome.syntaxDiagnostics` contains PARSER errors plus LEXER errors seen by the parser's lexer; the session
   takes LEXER diagnostics from the tokenizer and only PARSER ones from the parse, so nothing is reported twice.
6. The parse composition (`ComposedDocumentParser`) is separated from the ANTLR adapter (`antlr.tree`) and the converter
   adapter (`dsl`); the bundles in `registry` wire them (Abstract Factory), keeping the packages acyclic.
7. core-workspace calls dsl-tools only through core-document's public `DslConverter` adapter; the architecture rule
   allows `dsl..`/`parser..` only in `io.karpfen.ide.core.document.dsl` (stricter than allowing a second adapter
   package in core-workspace). `antlr4-runtime` became an `implementation` dependency of core-document.
8. `CodeEditorState` depends on `DocumentSession`, not `OpenDocument` (the editor does not need file-level state); the
   workbench owns dirty/conflict handling.
9. `LanguageServices.matchingBracket` returns `BracketMatch(open, close)` instead of a `Pair`; the default facade is
   created by `defaultLanguageServices(analysis)` in `core.lang.services` (a factory named like the interface in the
   root package would create a package cycle).
10. Key handling is Compose-free: `KeyStroke`/`KeyShortcut`/`HostPlatform` in `ui.foundation.keys`;
    `CommandRegistry.commandFor(KeyStroke)` instead of a Compose `KeyEvent` (converted with `KeyEvent.toKeyStroke()`);
    `Command.isEnabled` is a `StateFlow<Boolean>`.
11. `CodeEditor(state, modifier, onNavigate, overlays, theme)`: the popups plug in through `EditorOverlays`, so VIEW and
    POPUPS own disjoint files.
12. Additions: `UndoManager.breakCoalescing()`, `Workspace.contexts/isAnalyzing/awaitAnalysis()`, `DiagnosticCodes`,
    `CompletionKind` values, `ThemeMode.SYSTEM`, `OpenResult`/`SaveResult` result types (I/O errors are values).
13. Implicit contexts: a directory that contains a descriptor is covered by it; its files not named by a descriptor are
    not analyzed (they still get syntax diagnostics).

## 7. Open questions for the owner

1. **Editing `*.kenv.json` in phase 1.** The editor supports the three karpfen languages only, so
   `DocumentManager.open` returns `OpenResult.Unsupported` for descriptors, although the project tree offers "New
   Environment file". Should P1 add a plain-text (or JSON) document kind, or open descriptors in the system editor?
2. **Implicit contexts next to descriptors** (decision 13 above): directory-level coverage was chosen; the alternative
   is that every karpfen file not named by any descriptor joins an implicit context of its directory.
3. **dsl-tools console output in the IDE.** The converters `println` while parsing; the CLI redirects `System.out`, the
   IDE currently lets it reach the console. Redirect it to a logger in the app as well, or wait for dsl-tools to adopt
   SLF4J (proposal §5.1 item 6)?
4. **Menu accelerators vs. editor keymap.** Editor commands appear in the window menu with shortcuts and are also bound in
   the editor keymap; WP-APP/WP-WORKBENCH must make sure one key press runs the action once (e.g. menu items of editor
   commands show the shortcut but the editor consumes the key event first).

