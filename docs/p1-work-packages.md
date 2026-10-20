# P1 — Work packages

Status: v1.1, 2026-10-20 (contracts step; v1.1 applies the contract review, see [§6](#6-decisions-of-the-contracts-step-that-refine-the-spec)
and [§7](#7-review-findings-not-adopted)). Companion to [p1-text-editing-spec.md](p1-text-editing-spec.md) (the
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
   That includes `verifyModuleDependencies` and `architecture-tests`: ANTLR/converter confinement; Compose-, AWT- and
   Swing-free packages (an explicit list in `ComposeFreeStateTest`: `ui.editor.model..`, `ui.editor.state`,
   `ui.workbench.{state,command,files,settings,templates}`, `ui.foundation.command`); no package cycles in core and UI
   modules; UI layering (`UiLayeringTest`: the editor model sees no other editor package, editor `view` and `popup`
   meet only through `overlay`, editor `state` sees no view/popup/overlay/theme, workbench `state` sees no workbench
   view package, the root package or `files.desktop`, ports in `files` see no adapters); the P0 layer rules.
   Compose-side helper state (e.g. a hoisted `TreeViewState` for a widget) belongs in view packages and may be named
   `*State`; a new Compose-free state-holder package is a contract change (§5).
3. No `TODO(` remains in owned files at merge time.
4. **No test executes code of a WP that is not merged yet.** Use the fakes listed per WP (or write your own in your test
   sources). Every interface at a seam exists so it can be faked.
5. Spec §1 and §1a apply everywhere: one responsibility per class (≤ ~300 lines), constructor injection, no stateful
   `object`s, flows instead of listeners, immutable values across threads, time via `kotlin.time.TimeSource` /
   coroutine virtual time, dispatchers and file-system roots injected, SLF4J only (no `println` except CLI output),
   explicit API + KDoc on every public declaration in `core/*`, KDoc on public UI API as well.
6. Randomized tests use fixed seeds. Budgets are asserted with 5x slack and the measured value is reported through
   JUnit's `TestReporter` (it is written to the test report; the core test classpaths have no SLF4J binding, so SLF4J
   output would be dropped), as the spec requires.
7. Build files: a WP may only touch its own module's `build.gradle.kts`, and only to add **test-only** libraries
   (`testImplementation`/`testRuntimeOnly`). All production dependencies are in place. WPs sharing a module
   (TOKENS + PARSE in core-document; EDITOR-MODEL + EDITOR-VIEW + EDITOR-POPUPS in ui-editor) add lines only; the later
   merge resolves the trivial conflict.
8. Shared test fixtures (`core/core-document/src/testFixtures`) are CONTRACT. Use them through
   `testImplementation(testFixtures(project(":core:core-document")))` (already declared in every module that needs
   them): `Examples.files()`, `Examples.contexts()`, `Examples.copyTo(dir)`, `Mutations.random(text, seed, count)`,
   `Mutations.prefixes(text)`, `SyntheticKstates(n)` / `SyntheticKstates.withMinLines(n)`, and the IDE-authored
   environment descriptors `DescriptorExamples.text(name)` / `DescriptorExamples.copyExplicitContext(dir)` (valid,
   unknown key + missing file, invalid JSON; expected findings in `ide-examples/README.md`). Do not invent descriptors.
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
`registry/DefaultLanguageRegistry.kt`, `registry/KarpfenLanguageSupports.kt`, `dsl/DslConverter.kt`,
`dsl/DslErrorLocator.kt`, `session/DocumentId.kt`, `session/DocumentSession.kt` (incl. `ExternalDiagnosticSources`),
`session/DocumentSessionConfig.kt`, `session/VersionedDiagnostics.kt`.
**Shared ANTLR mechanics** of WP-TOKENS and WP-PARSE, complete and tested in the contracts step (decision 16):
`antlr/CodePointOffsets.kt`, `antlr/IdeErrorListener.kt`, `antlr/KarpfenLexers.kt`, `antlr/LexerGaps.kt`,
`antlr/EvalBodies.kt`. The test fixtures `src/testFixtures/**` (incl. `ide-examples/**` and `DescriptorExamples.kt`)
and the tests `src/test/kotlin/io/karpfen/ide/core/document/fixtures/ExamplesFixtureTest.kt`, `…/dsl/DslToolsSmokeTest.kt`,
`…/dsl/FixtureConversionTest.kt`, `…/antlr/CodePointOffsetsTest.kt`, `…/antlr/IdeErrorListenerTest.kt`,
`…/antlr/LexerGapsTest.kt`, `…/antlr/EvalBodiesTest.kt`.

core/core-workspace (`…/core/workspace/`): `Workspace.kt`, `WorkspaceOptions.kt`, `files/FileKind.kt`,
`files/WorkspaceTree.kt`, `files/FileWatcher.kt`, `document/DocumentManager.kt`, `document/OpenDocument.kt`,
`document/TextFileStore.kt`, `document/SourceLoader.kt`, `environment/EnvironmentDescriptor.kt`,
`environment/EnvironmentDescriptorReader.kt`, `context/ModelingContext.kt`, `context/ContextResolution.kt`,
`context/ContextResolver.kt`, `analysis/ProjectAnalysis.kt`, `analysis/ProjectAnalyzer.kt`,
`problems/WorkspaceProblems.kt`.

core/core-lang (`…/core/lang/`): `LanguageServices.kt`, `LanguageDocument.kt`, `Highlighting.kt`, `Completion.kt`, and
the *signatures* of `defaultLanguageServices(…)` (with `ProviderFailure`) and `karpfenLanguageFeatures()` in
`services/LanguageServicesFactory.kt` (the file itself is owned by WP-LANG, see below).

ui/ui-foundation (`…/ui/foundation/`): `keys/KeyStroke.kt`, `command/Command.kt`.

ui/ui-editor (`…/ui/editor/`): `model/caret/Caret.kt`, `model/action/EditorAction.kt` (incl. `EditorActionContext`,
`EditorActionExecutor`), `model/action/EditorActionIds.kt`, `model/navigation/GoToLineQuery.kt`,
`state/CodeEditorState.kt`, `state/EditorPopupModel.kt` (what the popups may use), `state/EditorOverlayController.kt`
(complete; shared by VIEW and POPUPS), `overlay/EditorOverlays.kt` (the slot API between VIEW and POPUPS).

ui/ui-workbench (`…/ui/workbench/`): `state/WorkbenchState.kt`, `state/WorkbenchServices.kt`,
`command/WorkbenchCommandIds.kt`, `settings/IdeSettings.kt`, `settings/SettingsRepository.kt`,
`files/ProjectFileOperations.kt` (the ports `ProjectFileOperations`, `DeleteOutcome`, `FolderChooser`), and the
*signature* of `defaultWorkbenchServices(…)` in `DefaultWorkbenchServices.kt` and of `Workbench(…)` in `Workbench.kt`.
The *public members as declared* of the sub-states (`state/ProjectTreeState.kt`, `EditorTabsState.kt`,
`OutlineState.kt`, `ProblemsState.kt`, `StatusBarState.kt`, `DialogState.kt`) are part of the contract (other WPs may
use them, e.g. WP-APP `dialogs.ask(DialogRequest.Error(…))`); WP-WORKBENCH owns the files and may add members.

tools/cli (`…/tools/cli/`): `validate/ValidationReport.kt`.

architecture-tests: `KarpfenModules.kt`, `ForeignApiConfinementTest.kt`, `ComposeFreeStateTest.kt`,
`PackageCycleTest.kt`, `UiLayeringTest.kt` and the P0 tests.

Contract tests (they pin the complete values and wiring; extend coverage in your own test files, do not edit these):
`core/core-text/src/test/kotlin/io/karpfen/ide/core/text/TextValuesContractTest.kt`,
`core/core-document/src/test/kotlin/io/karpfen/ide/core/document/DocumentContractTest.kt`,
`core/core-workspace/src/test/kotlin/io/karpfen/ide/core/workspace/WorkspaceContractTest.kt`,
`core/core-lang/src/test/kotlin/io/karpfen/ide/core/lang/LanguageContractTest.kt`,
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
  Paste/Cut/Command/External never coalesce, `group`, `breakCoalescing`); caret mementos of merged steps (a group or
  coalesced run restores the first change's `caretsBefore` on undo and the last change's `caretsAfter` on redo, see
  `UndoManager`); `isAtSavePoint` through undo/redo; `mapOffset` with both biases against a reference and `null` after
  eviction; `LineIndex` edge cases (empty text, trailing newline, only newlines, very long line); `LineSeparators`
  round trip and mixed detection; `apply` rejects overlapping edits and returns null for no-ops.
- **Must not touch**: any other module.

### WP-FOUNDATION — ui-foundation

- **Owns**: all of `io.karpfen.ide.ui.foundation` except CONTRACT files. Skeletons (`ui/ui-foundation/…/ui/foundation/`):
  `keys/ComposeKeys.kt`, `command/CommandRegistry.kt`, `theme/KarpfenTheme.kt`, `icons/KarpfenIcons.kt`,
  `layout/SplitPane.kt` (`SplitPane`, `ToolWindow`), `widgets/TabStrip.kt`, `widgets/TreeView.kt`, `widgets/StatusBar.kt`.
- **Implements**: `CommandRegistry` (`DefaultCommandRegistry`), `KeyEvent.toKeyStroke()`, `KeyShortcut.displayText`,
  `KeyShortcut.toMenuShortcut` (WP-APP binds menu accelerators with it, decision 15), palettes/typography,
  `KarpfenTheme`, icons (`ImageVector.Builder`, no material-icons-extended), the widgets. Hoisted widget state
  (e.g. a `TreeViewState`) may use Compose; `ui.foundation.command` must stay Compose-free (rule 2).
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
- **Implements**: `Tokenizer` ×3 on the shared ANTLR mechanics, never re-implementing them: `KarpfenLexers.lex`
  (listener replaced, LEXER diagnostics, `LexedText.rangeOf` for every index); KMeta/KModel comments and unrecognized
  input from `LexerGaps.scan` (COMMENT and ERROR tokens); KStates hidden `LINE_COMMENT` tokens; EVAL bodies and their
  pieces from `EvalBodies.find`/`scan` (mapping documented on `KstatesTokenizer`: `//` → COMMENT + `eval-double-slash`
  WARNING); `EVENT` as OPERATION in action blocks, KEYWORD in CONDITION blocks (block structure on brace tokens).
  `DocumentSession` (tokens via mapLatest without debounce, initial tokens synchronous; debounced, cancellable parse
  with the initial parse undebounced; `lastGoodParse`; merged, deduplicated, sorted `VersionedDiagnostics` mapped to
  the newest version; `setExternalDiagnostics` thread-safe (any thread); `close`). Keep the no-arg constructors (used
  by `registry/KarpfenLanguageSupports.kt`) and the factory semantics.
- **Fakes**: `DocumentParser` is WP-PARSE: build sessions with `LanguageSupport(language, realTokenizer, fakeParser)`
  where the fake returns hand-built `ParseOutcome`s (also with syntax diagnostics, to test `lastGoodParse`).
  Time: `StandardTestDispatcher` in `DocumentSessionConfig` + `runTest` virtual time.
- **Must satisfy**: spec §3.1 (all MUST rules), §3.2, §3.4 (flows, debounce, merging) and the tokenize budget
  (5,000-line KStates < 30 ms, asserted with 5x slack, measured value reported; use `SyntheticKstates.withMinLines(5000)`),
  §11 item 2 (zero LEXER diagnostics for every example) and item 3 for tokenizers (never throw on
  `Mutations.random(…, count = 10_000)` over the examples and on every prefix of one KStates example; token invariants
  hold on all of them). Rule from §3.1: every non-whitespace character in exactly one token — test it on all mutations.
- **Must not touch**: `antlr/tree`, `dsl`, `parse`, `registry`, `language`, the root package, the shared files in the
  `antlr` root package (`CodePointOffsets.kt`, `IdeErrorListener.kt`, `KarpfenLexers.kt`, `LexerGaps.kt`,
  `EvalBodies.kt`), fixtures.

### WP-PARSE — core-document: ANTLR parse adapter, tree walkers, dsl-tools adapter

- **Owns**: packages `io.karpfen.ide.core.document.antlr.tree` and `io.karpfen.ide.core.document.dsl` (except the
  CONTRACT files `dsl/DslConverter.kt`, `dsl/DslErrorLocator.kt`), and the owned file `parse/ParserSeams.kt`.
  Skeletons (`core/core-document/…/core/document/`): `antlr/tree/KmetaSyntaxAnalyzer.kt`,
  `antlr/tree/KmodelSyntaxAnalyzer.kt`, `antlr/tree/KstatesSyntaxAnalyzer.kt`, `dsl/DefaultDslConverter.kt`,
  `dsl/QuotedNameErrorLocator.kt`, `dsl/StandaloneSemanticsAdapters.kt`, `parse/ParserSeams.kt`
  (`ComposedDocumentParser` and the internal seams `SyntaxAnalyzer`, `SyntaxAnalysis`, `StandaloneSemantics`,
  `StandaloneResult`, which WP-PARSE may refine). Tests in `…/core/document/antlr/tree/`, `…/dsl/` (new files only),
  `…/parse/`.
- **Implements**: `SyntaxAnalyzer` ×3 (`CommonTokenStream` over `KarpfenLexers.create`, one `IdeErrorListener`
  attached to lexer and parser, default recovery; walkers for outline, `SyntaxIndex`, folding, semantic spans;
  tolerate missing tokens and error nodes; name ranges inside quotes; every extraction listed in spec §3.3, with
  `SymbolDeclaration.typeName`/`container`, `SymbolReference.qualifier` and `OutlineNode.detail` exactly as documented
  on those types). Shared mechanics, never re-implemented: KStates EVAL references (MODEL_PATH from PATH_REFERENCE
  pieces, MACRO from MACRO_REFERENCE pieces), their semantic spans and EMBEDDED_CODE folding come from
  `EvalBodies.find`/`scan` over the stream's tokens (lexical, so they agree with the tokenizer); COMMENT folding from
  `LexerGaps.scan` (KMeta/KModel) and the hidden `LINE_COMMENT` tokens (KStates). `ComposedDocumentParser` (syntax,
  then standalone semantics only for `SemanticMode.STANDALONE` and clean syntax; collaborator exceptions →
  `internal-error`), `DslConverter` (`DefaultDslConverter`: the three converters, exceptions → DSL_TOOLS ERROR
  diagnostics), `DslErrorLocator` (`QuotedNameErrorLocator`), `KmetaStandaloneSemantics`, `KstatesStandaloneSemantics`.
  Keep the constructors used by `registry/KarpfenLanguageSupports.kt` (`KmetaSyntaxAnalyzer()`, …,
  `KmetaStandaloneSemantics(converter)`, `ComposedDocumentParser(language, syntax, semantics)`); add parameters only
  with defaults.
- **Fakes**: none needed beyond core-text (merged). The tokenizer is not needed (first token for error location is
  derived from the text).
- **Must satisfy**: spec §0 (ANTLR only via the adapter), §3.3 (all MUST rules), the parse budget (5,000-line KStates
  < 150 ms, 5x slack, reported), §11 item 2 (zero PARSER diagnostics on every example; standalone conversion succeeds
  for every example `.kmeta` except `EVENTS.kmeta`, which is parsed with `SYNTAX_ONLY`, and every `.kstates`), item 3 for
  parser/outline/index (never throw on 10,000 mutations and all prefixes; `OutlineNode`/`SymbolDeclaration` invariants
  hold, i.e. clamp ranges of conjured tokens). Error location tests: a message naming `'Vector'` points at the quoted
  name; unknown messages point at the first token. A test that every MODEL_PATH/MACRO reference inside an EVAL body
  covers the `inner` range of a PATH_REFERENCE/MACRO_REFERENCE piece (same scanner as the tokenizer).
- **Must not touch**: `antlr/lex`, `session`, `syntax`, `language`, `registry`, the root package, the CONTRACT files in
  `parse`, the shared files in the `antlr` root package, the existing tests in `…/dsl/` and in the `…/antlr/` root
  test package, fixtures.

### WP-EDITOR-MODEL — ui-editor, Compose-free model

- **Owns**: packages `io.karpfen.ide.ui.editor.model.caret`, `.model.action`, `.model.find`, `.model.completion`,
  `.model.navigation` except CONTRACT files. Skeletons (`ui/ui-editor/…/ui/editor/model/`): `caret/CaretModel.kt`,
  `action/AutoClosedPairs.kt`, `action/EditorActionRegistry.kt` (registry and keymap), `action/DefaultEditorActions.kt`
  (wiring; keep the function signature), `action/NavigationActions.kt`, `action/UiRequestActions.kt`,
  `action/TypingActions.kt`, `action/LineActions.kt`, `action/ClipboardActions.kt`, `action/HistoryActions.kt`,
  `find/FindModel.kt`, `completion/CompletionSessionModel.kt`.
- **Implements**: `CaretModel` (movement by char/word/line/page/document, smart Home, selection extension, select
  word/line/all, version-stamped carets mapped through foreign changes, preferred column in display columns with the
  factory's `tabWidth()`, `breakCoalescing()` on every explicit move), `AutoClosedPairs` (version-stamped, mapped on
  query), every action of `EditorActionIds` (one undo step each, caret mementos before/after), `typeTextAction`
  (auto-close and over-type of *tracked* closers only), `EditorActionRegistry`, `defaultEditorKeymap` (bindings
  listed on `EditorKeymap`), `FindModel` and `CompletionSessionModel` (triggers, filtering and ranking, close rules,
  `handleKey`; observes `caret.state` itself; versioned `CompletionSource` calls, stale results dropped), both editing
  only through internal replace/accept `EditorAction`s run by their `EditorActionExecutor`.
- **Fakes**: `EditorActionContext`, `ClipboardPort`, `EditorUiPort`, `CompletionSource` (hand-written fakes); an
  `EditorActionExecutor` that runs the action in the fake context; real `AutoClosedPairs`; tokens as hand-built
  `TokenizeResult`s (e.g. EMBEDDED_* tokens to mark an EVAL body for toggle comment); `KeyStroke`s built directly
  (values are complete). Real `EditableText` (WP-TEXT merged). Coroutines: `UnconfinedTestDispatcher`.
- **Must satisfy**: spec §7.1 (all bullets), "every action is exactly one undo step", undo/redo restore carets (rule of
  `UndoManager`). Tests per action group, per movement (incl. Up/Down over tab lines with tab widths 2 and 4), per find
  option (invalid regex → error state), completion ranking/stability, a typed `)` before a user-typed `)` is inserted
  (not over-typed), Backspace inside a user-typed `()` deletes one character.
- **Must not touch**: `state`, `view`, `theme`, `overlay`, `popup`; CONTRACT files.

### WP-WORKSPACE — core-workspace

- **Owns**: all of `io.karpfen.ide.core.workspace` except CONTRACT files. Skeletons
  (`core/core-workspace/…/core/workspace/`): `DefaultWorkspace.kt` (incl. `WorkspaceCollaborators`),
  `files/WatchServiceFileWatcher.kt`, `files/WorkspaceScanner.kt`, `document/DefaultDocumentManager.kt`,
  `document/AtomicTextFileStore.kt`, `document/DefaultSourceLoader.kt`, `environment/JsonEnvironmentDescriptorReader.kt`,
  `context/DefaultContextResolver.kt`, `analysis/DefaultProjectAnalyzer.kt`, `problems/WorkspaceProblemsAggregator.kt`.
- **Implements**: `Workspace` (scan, watch + 200 ms debounce, refresh, contexts incl. loose files, debounced 300 ms
  cancellable analysis, `isAnalyzing`, `awaitAnalysis` returning once `analysis` and `problems` reflect it, pushing
  `ExternalDiagnosticSources.PROJECT` diagnostics into open sessions, `problems` by the single rule documented on
  `WorkspaceProblems`) as a coordinator over internal collaborators (tree refresher, analysis scheduler, diagnostics
  publisher, problems aggregator); the injected collaborators of the `Workspace(…)` factory are used, never created
  inside. `FileWatcher` (recursive `WatchService`). `SourceLoader` (UTF-8 with replacement + WARNING on the first
  U+FFFD, BOM removed and flagged, separators normalized + INFO on the first minority line break). `TextFileStore`
  (BOM-aware read/write). `DocumentManager`/`OpenDocument` (loading through the `SourceLoader`, findings as external
  source `FILE`, `WorkspaceOptions.timeSource` for every `EditableText`, dirty flag from the save point, atomic save
  restoring separators and BOM, self-induced event suppression, reload as one `External` change when clean, conflict
  when dirty or deleted, `SYNTAX_ONLY` sessions for `EVENTS.kmeta`; every text mutation on the workspace scope's
  dispatcher). `EnvironmentDescriptorReader` (on normalized text; unknown keys → WARNING, JSON value ranges).
  `ContextResolver` (rules on the interface, `looseFiles`). `ProjectAnalyzer` (through `DslConverter` only — never
  dsl-tools directly, enforced by architecture tests; `AnalyzedFile` with lexical and file diagnostics for context and
  loose files).
- **Fakes**: a `FileWatcher` fake that emits `FileChange`s on demand (the JDK's macOS watch service polls slowly; at most
  one real-watcher test, tagged slow-tolerant with generous timeouts); optionally an in-memory `TextFileStore` or
  `SourceLoader`. Real tokenizers/parsers/`DslConverter` (WP-TOKENS, WP-PARSE merged). Virtual time via test dispatchers
  and a `TestTimeSource` in `WorkspaceOptions`. Real folders via `Examples.copyTo(@TempDir)` and
  `DescriptorExamples.copyExplicitContext(@TempDir)`.
- **Must satisfy**: spec §4 (all bullets) and §11 item 2 (every example context analyzes with zero DSL_TOOLS errors).
  Tests: tree order and ignore rules; FileKind; descriptor reading over `DescriptorExamples` (valid, invalid JSON,
  unknown key, missing file with range); explicit and implicit contexts incl. several metamodels and loose files;
  analysis of every example context; the problems rule (open document, unopened context file, loose file,
  descriptor); external-change reload and conflict; save round trip with CRLF/CR files and with a BOM; malformed
  UTF-8.
- **Must not touch**: other modules; CONTRACT files.

### WP-LANG — core-lang

- **Owns**: all of `io.karpfen.ide.core.lang` except the CONTRACT root files (`LanguageServices.kt`,
  `LanguageDocument.kt`, `Highlighting.kt`, `Completion.kt`). Skeletons (`core/core-lang/…/core/lang/`):
  `feature/LanguageFeatures.kt` (SPI with `FeatureRequest`, may be extended), `context/CaretContext.kt`,
  `symbols/TypeCatalog.kt` (values complete, `ContextTypeCatalog` to implement), `modelpath/ModelPathResolver.kt`,
  `highlight/DefaultHighlightProvider.kt`, `brackets/TokenBracketMatcher.kt`, `kmeta/Kmeta{LanguageFeatures,
  CompletionProvider,NavigationProvider,HoverProvider}.kt`, the same four files in `kmodel/` (`Kmodel…`) and `kstates/`
  (`Kstates…`), `services/DefaultLanguageServices.kt`, `services/LanguageServicesFactory.kt` (keep the two public
  signatures and `ProviderFailure`). The shared mechanics (`CaretContextDetector`, `TypeCatalog`, `ModelPathResolver`)
  and their value types may be refined.
- **Implements**: `LanguageServices` facade (delegation per language, never throws, logs provider failures and reports
  them to `onProviderFailure`), highlighting (lexical + semantic overlay when versions match), per-language
  completion/navigation/hover per spec §5, bracket matching, and the shared collaborators: caret-context detection,
  `TypeCatalog` (types of the document and the context with members and declaration `Location`s; hover texts from
  `TypeInfo.hoverText`/`MemberInfo.signature`), model-path resolution over `TypeInfo`. Every provider follows the
  staleness rule on `LanguageDocument` and fills `Location.version`.
- **Fakes**: none for code; project knowledge is built directly: `ProjectAnalysis`/`ContextAnalysis`/`AnalyzedFile`/
  `ModelingContext` are complete values, metamodels/models/state machines come from `DslConverter()` (WP-PARSE) over
  `Examples.contexts()`, parse outcomes from `defaultLanguageRegistry()`. Pass `MutableStateFlow(analysis)` to
  `defaultLanguageServices`. Do not use WP-WORKSPACE's analyzer.
- **Must satisfy**: spec §5 (completion by context for all three languages, definition/references, hover, matching
  bracket, structure with per-language strategies and shared collaborators) and §11 item 3 for highlight, completion,
  definition and hover (never throw on 10,000 mutations of the examples and every prefix of one KStates example). The
  robustness tests call `defaultLanguageServices(…, onProviderFailure = recorder)` and assert the recorder stays empty
  (the facade's catch-all must not hide provider exceptions).
- **Must not touch**: other modules; the CONTRACT root files.

### WP-EDITOR-VIEW — ui-editor view, state, theme

- **Owns**: packages `io.karpfen.ide.ui.editor.state` (except CONTRACT files `CodeEditorState.kt`,
  `EditorPopupModel.kt`, `EditorOverlayController.kt`), `io.karpfen.ide.ui.editor.view`, `io.karpfen.ide.ui.editor.theme`.
  Skeletons (`ui/ui-editor/…/ui/editor/`): `state/DefaultCodeEditorState.kt`, `state/EditorViewport.kt`,
  `view/CodeEditor.kt`, `view/EditorStateFactories.kt` (`rememberCodeEditorState`, `systemClipboard`),
  `view/LineLayoutCache.kt`, `theme/EditorTheme.kt` (palettes and `LocalEditorTheme`; the data class is frozen).
- **Implements**: `CodeEditorState` as a coordinator over internal collaborators of the `state` package (action-context
  adapter implementing `EditorActionContext`/`EditorUiPort` with one `AutoClosedPairs` per editor; completion source
  implementing the versioned `CompletionSource`; highlight pipeline and bracket tracker on `backgroundDispatcher`;
  hover/definition resolver waiting (bounded) for a current parse; `enabledActions`; pending `scrollRequest` and
  `focusRequested` with their consume functions; `goToLine`), `EditorViewport`, `CodeEditor` (rendering,
  virtualization, layout cache, gutter, decorations, input incl. IME/dead keys/macOS Option combinations and
  `SemanticsActions.InsertTextAtCursor`, keymap routing with completion first, consuming exactly the strokes it
  handles (decision 15), mouse, scrollbars, caret reveal with 3-line margin, applying pending requests when composed),
  hosting `EditorOverlays.Header`/`Popups` with an `EditorOverlayHost` implementation, editor themes, the lazy system
  clipboard port with headless fallback.
- **Fakes**: a fake `LanguageServices` (fixed spans/items/locations); `EditorOverlays.None` or a test overlay that
  records host calls (POPUPS is developed in parallel). Real sessions via `DocumentSession(…)` with
  `defaultLanguageRegistry()` (TOKENS and PARSE merged), real models (EDITOR-MODEL merged), real foundation; a test
  dispatcher as `backgroundDispatcher`.
- **Must satisfy**: spec §7.2 (rendering, input, mouse, scrolling, `scrollTo`) and the headless benchmark (10,000-line
  KStates via `SyntheticKstates.withMinLines(10_000)`, scrolled to the middle, average frame time after warm-up
  < 50 ms, reported), §11 item 4 (headless Compose tests: typing incl. `performTextInput`, selection by mouse and keys,
  Cmd/Ctrl+click navigation callback, hover after 600 ms with diagnostics, scroll-to-caret). Also: `scrollTo(center)`
  and `requestFocus()` issued before the first composition take effect once `CodeEditor` is composed; a handled key
  stroke is consumed and an unhandled one is not; `enabledActions` follows selection and undo history.
- **Must not touch**: `model/**`, `popup/**`, `overlay/**`, CONTRACT files.

### WP-EDITOR-POPUPS — ui-editor popups

- **Owns**: package `io.karpfen.ide.ui.editor.popup`. Skeletons (`ui/ui-editor/…/ui/editor/popup/`): `EditorPopups.kt`,
  `CompletionPopup.kt`, `FindReplaceBar.kt`, `GoToLinePopup.kt`, `HoverTooltip.kt`, `EditorContextMenu.kt`.
- **Implements**: `EditorPopups : EditorOverlays` — `Header` (find/replace bar) and `Popups` (completion list, go-to-line,
  hover tooltip, context menu), each composable rendering immutable state and calling the host's `EditorPopupModel`
  (go-to-line through `goToLine`, context-menu enablement through `isEnabled`, focus return through `requestFocus`).
- **Fakes**: a fake `EditorPopupModel` (the narrow interface, not `CodeEditorState`) wired to real models
  (`CaretModel`, `FindModel`, `CompletionSessionModel` from EDITOR-MODEL, with an `EditorActionExecutor` running the
  actions in a fake `EditorActionContext`) and a real `EditorOverlayController`; a fake `EditorOverlayHost` with fixed
  geometry; an `EditorTheme` built in the test (the palettes are WP-EDITOR-VIEW's). Do not call
  `CodeEditorState(…)`/`rememberCodeEditorState` (VIEW, not merged).
- **Must satisfy**: spec §7.2 Popups bullet: completion ≤ 12 rows with kind badge/label/detail; find bar with Aa/W/.*
  toggles, "n/m" counter, Enter/Shift+Enter, Escape returns focus (`requestFocus`); go-to-line `line[:col]`;
  hover tooltip; context menu Cut/Copy/Paste/Go to Definition/Toggle Comment by action id, disabled entries greyed
  out. Headless tests per popup.
- **Must not touch**: everything outside `popup`.

### WP-WORKBENCH — ui-workbench

- **Owns**: all of `io.karpfen.ide.ui.workbench` except CONTRACT files and INTEGRATION's test package
  `io.karpfen.ide.ui.workbench.e2e`. Skeletons (`ui/ui-workbench/…/ui/workbench/`): `Workbench.kt` (body),
  `DefaultWorkbenchServices.kt` (body), `state/DefaultWorkbenchState.kt`, `state/ProjectTreeState.kt`,
  `state/EditorTabsState.kt`, `state/OutlineState.kt`, `state/ProblemsState.kt`, `state/StatusBarState.kt`,
  `state/DialogState.kt` (sub-states may get new members; their declared public members are frozen, see §3),
  `settings/JsonSettingsRepository.kt`, `templates/FileTemplates.kt`, `files/desktop/DesktopFileOperations.kt`,
  `projecttree/ProjectTreeView.kt`, `tabs/EditorArea.kt`, `outline/OutlineView.kt`, `problems/ProblemsView.kt`,
  `statusbar/WorkbenchStatusBar.kt`, `dialog/WorkbenchDialogs.kt`, `welcome/WelcomeView.kt`.
- **Implements**: `WorkbenchState` (Mediator) as a coordinator over internal collaborators of the `state` package
  (command registrar, tool-window layout, unsaved-changes flow, file-operations flow): `openFolder`, `openFile` (files
  the editor cannot open go to `openExternally`, decision 14), `navigateTo` (versioned ranges mapped with
  `mapOffset`, else clamped), the project-tree intents `createFile`/`rename`/`delete`/`reveal` and the conflict banner
  intent `resolveConflict` as documented on `WorkbenchState`, close/exit flows; the sub-states (`ProblemsState` and the
  status-bar counts only map `Workspace.problems`); `Workbench` composable and views (no logic; they call the intents);
  `SettingsRepository` (JSON, corrupt-file backup named from the injected `Clock`); templates; desktop file operations
  and folder chooser (blocking work on `WorkbenchServices.ioDispatcher`); command registration (`WorkbenchCommandIds`;
  editor commands under `EditorActionIds` with shortcuts from `defaultEditorKeymap(platform)` and enablement from the
  active editor's `enabledActions`, forwarded to it); light/dark theme provision.
- **Fakes**: fake `FolderChooser`, `ProjectFileOperations` (or the real one on `@TempDir`), `WorkbenchServices` with
  fakes where useful, a test dispatcher as `ioDispatcher` and as the editors' background dispatcher (through an
  `EditorStateFactory` that calls `CodeEditorState(…, backgroundDispatcher = …)`); real everything else (all
  dependencies merged). Settings directory: `@TempDir`; clock: `Clock.fixed`.
- **Must satisfy**: spec §8 (all bullets) with unit tests per sub-state (Compose-free) and for the mediator flows (new
  file from each template, rename and delete of open files, external conflict Reload / Keep mine, navigateTo into an
  unopened file centres the caret), headless UI tests for the layout, tree open, tab close with unsaved changes,
  problems navigation (also to a descriptor diagnostic → opened externally), status bar; settings persistence and
  corrupt-file recovery.
- **Must not touch**: other modules; CONTRACT files; `…/ui/workbench/e2e`.

### WP-APP — app/ide and tools/cli

- **Owns**: package `io.karpfen.ide.app` and `io.karpfen.ide.tools.cli` (incl. `validate`) except the CONTRACT file
  `validate/ValidationReport.kt` and INTEGRATION's `e2e` test packages. Skeletons: `app/ide/…/app/IdeApplication.kt`,
  `app/ide/…/app/IdeMenus.kt`, `app/ide/…/app/CrashHandling.kt`, `app/ide/…/app/Main.kt` (rewrite: `main(args)`,
  optional folder argument, `KARPFEN_IDE_EXIT_AFTER_MS` kept), `tools/cli/…/tools/cli/Main.kt` (rewrite: `main(args)` →
  exit code; keep a one-line `USAGE`), `tools/cli/…/tools/cli/CliArguments.kt`,
  `tools/cli/…/tools/cli/validate/ValidateCommand.kt`, `…/validate/ReportFormatter.kt`,
  `…/validate/StandardOutputGuard.kt`; new resources `app/ide/src/main/resources/logback.xml` and
  `tools/cli/src/main/resources/logback.xml`.
- **Implements**: the composition root (`IdeApplication`), menus from `CommandRegistry` + `IdeMenus.specs` through an
  internal Compose-free `resolveMenus(specs, registry)` that `IdeMenuBar` only renders (accelerators bound with
  `toMenuShortcut`, decision 15), native macOS menu bar, window title and bounds, close → `requestExit`, crash handling
  with a dialog (`WorkbenchState.dialogs.ask(DialogRequest.Error(…))` once the workbench runs), logging config
  (console WARN, rolling file `~/.karpfen-ide/logs/ide.log`); CLI argument parsing, `validate` (workspace with the
  injected `WorkspaceOptions` without watching, `refresh`, `awaitAnalysis`, report `Workspace.problems` — no own
  tokenizing or merging; analysis inside the suspending `StandardOutputGuard.silenced`), text/JSON formatters, exit
  codes (1 on ERROR).
- **Fakes**: app tests must not run `WorkbenchState`/`Workbench` (WP-WORKBENCH may not be merged): test `IdeMenus.specs`,
  `resolveMenus` (with a fake `CommandRegistry` if WP-FOUNDATION is not merged yet), `CrashHandling` with a recording
  callback. CLI tests use the real workspace (WORKSPACE/LANG merged) over `Examples.copyTo(@TempDir)` and
  `DescriptorExamples.copyExplicitContext(@TempDir)`, with test dispatchers in the `WorkspaceOptions`.
- **Must satisfy**: spec §9 and §11 item 5 (`karpfen validate <examples dir>` exits 0 with no ERROR output; a broken copy
  exits 1 with `path:line:col: error: … [code]`, one-based; descriptor findings are reported with their positions;
  JSON format parses).
- **Must not touch**: other modules; `logback.xml` must not be added anywhere else.

### INTEGRATION — end-to-end tests and documentation

- **Owns**: new test packages `…/e2e/` in `app/ide`, `tools/cli`, `ui/ui-workbench` and `ui/ui-editor`
  (`io.karpfen.ide.ui.editor.e2e`: `CodeEditor` together with `EditorPopups`, which neither VIEW nor POPUPS may test);
  `docs/p1-manual-test.md` (new); `README.md` (status, module table, run instructions).
- **Implements**: end-to-end tests over the merged system: open the examples folder in a `WorkbenchState`, edit, see
  diagnostics and completion, save/undo, external change; in `ui.editor.e2e` completion opening on typing with keys
  routed by the view, find-bar Escape returning focus, the context menu by right-click; DoD checks of spec §11 not
  already covered by a WP.
- **Must satisfy**: spec §11 items 1–7 (composite mode in CI; `KARPFEN_IDE_EXIT_AFTER_MS=6000 ./gradlew :app:ide:run
  --args=<examples dir>` exits 0 with no ERROR in the log; the 10-minute manual smoke test document for the owner).
  Also: the Problems view, the status-bar counts and `karpfen validate` agree on the examples plus
  `DescriptorExamples.copyExplicitContext`; one Cmd/Ctrl+Z in the editor undoes exactly one step (headless: the
  stroke is consumed by the editor and no command runs; the manual test covers the native macOS menu bar).
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
6. Shared mechanics are never copied into a second WP (spec §1a). If two WPs need the same mechanism and it is not in a
   CONTRACT file, that is a contract gap (step 3), not a reason to write it twice.

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
13. Implicit contexts: a directory that contains a descriptor is covered by it; its karpfen files not named by a
    descriptor are *loose files* (`ContextResolution.looseFiles`): tokenized and parsed (their syntax diagnostics
    appear in Problems and in the CLI), never converted.

Added by the contract review (v1.1):

14. **Files the editor cannot open** (`*.kenv.json` and other non-karpfen files): `OpenResult.Unsupported` makes the
    workbench open them in the system's default application (`ProjectFileOperations.openExternally`), also when
    navigating from the Problems view. Resolves open question 1 for phase 1 (see §8).
15. **Key routing** (resolves open question 4): the focused component handles and consumes its key strokes first
    (`CodeEditor` through its keymap, consuming exactly the strokes it handles; text fields such as the find bar
    natively); a menu accelerator only sees strokes nobody consumed (Swing key bindings skip consumed events; on macOS
    the screen menu bar delivers key equivalents as Java key events first). Editor commands in the menu carry
    `defaultEditorKeymap(platform).shortcutFor(id)` (one source of shortcuts), take their enablement from the active
    editor's `enabledActions` and are forwarded to the active editor. INTEGRATION proves one Cmd/Ctrl+Z = one undo
    step; the manual test covers the native macOS menu bar.
16. **Shared ANTLR mechanics** are complete and tested in the contracts step, like `CodePointOffsets`:
    `IdeErrorListener` (LEXER and PARSER diagnostics instead of console output), `KarpfenLexers` (the only map from a
    language to its generated lexer, listeners replaced), `LexerGaps` (comments and unrecognized input the KMeta/KModel
    lexers drop) and `EvalBodies` (EVAL bodies balanced on brace tokens like the dsl-tools parser, and their embedded
    pieces). WP-TOKENS and WP-PARSE both build on them and never copy them; EVAL references and folding come from the
    lexical scanner in both WPs, so highlighting, navigation and folding agree on every input.
17. **One problems rule**: `Workspace.problems` (`WorkspaceProblems`) is the only place that merges a file's
    diagnostics (open document → its session's diagnostics; other karpfen file, in a context or loose →
    `AnalyzedFile.ownDiagnostics` plus project diagnostics; descriptor → project diagnostics). The Problems view, the
    status bar and `karpfen validate` only map and filter it. The analyzer records tokenizer and loading diagnostics on
    every `AnalyzedFile`, loose files included. `SourceLoader` is the one loading rule (UTF-8 with replacement, byte
    order mark removed and restored on save, separators normalized, findings as external source
    `ExternalDiagnosticSources.FILE`); descriptor ranges refer to the normalized text.
18. **Editor contracts for parallel work**: the popups see `EditorPopupModel` (interface segregation), not the whole
    `CodeEditorState`; view requests are pending *state* with consume functions (`scrollRequest`, `focusRequested`),
    so requests made before `CodeEditor` is composed are not lost, and `navigationRequests` is the only event stream
    (`EditorOverlayHost.navigate`/`requestEditorFocus` were duplicates and are gone); `enabledActions`/`isEnabled`
    expose action enablement; `hoverAt` suspends; `bracketMatch` is versioned (`BracketMatchState`); the background
    dispatcher is injected. Find/replace and completion accept edit through `EditorAction`s run by an
    `EditorActionExecutor` (Command pattern for every edit); the completion model observes `caret.state` itself (no
    `onCaretMoved`) and calls a versioned `CompletionSource.items(snapshot, offset)`; auto-inserted closers are tracked
    per editor by `AutoClosedPairs` in the `EditorActionContext`; `CaretModel(…, tabWidth)` computes preferred columns
    with tabs expanded and breaks undo coalescing on explicit moves.
19. **Language services**: `FeatureRequest` (was `DocumentContext`; "context" now only means caret, modeling and action
    contexts) with `contextAnalysis`, also for `BracketMatcher`; the shared `TypeCatalog` (IDE-owned
    `TypeInfo`/`MemberInfo` with hover texts and declaration `Location`s) serves all three languages and `PathScope`
    is built on it, so no dsl-tools type appears in the providers' or the resolver's API; `LanguageDocument` has
    `lastGoodParse`, `isConsistent`, `currentParse` and a staleness rule, and `LanguageDocument.of(session)` tokenizes
    synchronously when the session's tokens lag; `Location.version` lets navigation map ranges into the current text;
    `defaultLanguageServices(…, onProviderFailure)` makes the facade's swallowed exceptions observable.
20. **Open/Closed for languages**: `LanguageSupport.matches(fileName)` resolves file names (the registry asks each
    support). The product family of a language is split by layer — `LanguageSupport` (tokenizer, parser) in
    core-document, `LanguageFeatures` (editor features) in core-lang — and `LanguageContractTest` checks that every
    registered language has features. Accepted closed per-language switches: `KarpfenLanguage` (enum), `FileKind.of`,
    `DslConverter` (one method per dsl-tools converter; the foreign API is closed), `DslModel` (sealed),
    `FileTemplateKind`, `KarpfenIcons`. A fourth language adds entries there plus one `LanguageSupport` and one
    `LanguageFeatures`; no existing language class changes.
21. **Injected time, threads and collaborators**: `WorkspaceOptions.timeSource` for the documents' `EditableText`s;
    every text mutation on the dispatcher of the workspace's scope (single writer); `setExternalDiagnostics` is
    thread-safe; the `Workspace(…)` factory takes `sourceLoader`, `descriptorReader`, `contextResolver` and `analyzer`;
    `CodeEditorState(…, backgroundDispatcher)`; `WorkbenchServices` is a plain class with `ioDispatcher`, and
    `defaultWorkbenchServices(…, backgroundDispatcher, ioDispatcher)` injects both everywhere;
    `SettingsRepository(…, clock)`; `ValidateCommand(…, workspaceOptions)`; `StandardOutputGuard.silenced` suspends.
22. **Symbol data for core-lang**: `SymbolDeclaration.typeName` (object types, property and relation targets, parameter
    and return types), the `container` rules, `SymbolReference.qualifier` (KModel keys → the enclosing object's type;
    KStates `SETOBJ`/`APPENDOBJ` relation → the source path; EVENT_NAME → the domain) and `OutlineNode.detail` per kind
    are documented on the types; WP-LANG relies on nothing else of WP-PARSE's output.
23. **Factory naming**: a factory named like its interface creates the default implementation and lives in the
    interface's package (`EditableText(…)`, `Workspace(…)`, `SourceLoader(…)`, `TypeCatalog()`, `CaretModel(…)`). A
    descriptive name is used when the factory assembles a product family (`defaultLanguageRegistry()`,
    `karpfenLanguageFeatures()`, `defaultEditorActions()`), lives in another package than its interface to keep the
    packages acyclic (`defaultLanguageServices(…)`) or in an adapter package (`desktopProjectFileOperations()`,
    `nativeFolderChooser()`), or provides a platform default (`defaultEditorKeymap(…)`, `defaultWorkbenchServices(…)`).
24. **Concrete state holders** (exception to decision 1): `EditorOverlayController` (complete, without collaborators —
    it is its own fake) and the workbench sub-states (`ProjectTreeState`, `EditorTabsState`, `OutlineState`,
    `ProblemsState`, `StatusBarState`, `DialogState`) stay public classes: plain state holders without I/O whose only
    collaborator is a coroutine scope, so tests use the real ones. Their declared public members are frozen (§3). I/O
    goes through ports (`ProjectFileOperations`, `FolderChooser` in `files`), whose desktop adapters live in
    `files.desktop`.
25. **Fat facades are coordinators**: `DefaultCodeEditorState`, `DefaultWorkbenchState` and `DefaultWorkspace` keep
    their interfaces but delegate to internal single-purpose collaborators listed in their KDoc (spec §1a, ≤ ~300
    lines per class).

## 7. Review findings not adopted

1. **A core-lang-owned `ProjectKnowledge` port instead of the workspace's `ProjectAnalysis`.** The providers need the
   complete per-context analysis (converted metamodels and models, every file's snapshot and parse outcome); a port
   would mirror `ContextAnalysis` field by field and need an adapter in every composition root. core-lang depends only
   on immutable values of core-workspace (no `Workspace` behaviour), `core/* → core/*` is allowed by the module matrix,
   and `TypeCatalog` (decision 19) already keeps the dsl-tools `meta.*` model out of the providers' APIs. That
   ui-editor sees core-workspace's values transitively through `api` is accepted for a UI module.
2. **Workbench sub-states and `EditorOverlayController` as interfaces with internal implementations.** See decision 24.
3. **A `bindAccelerator` flag on `MenuEntry.Item` (display-only menu shortcuts).** Not needed with decision 15, and
   Compose's `MenuBar` cannot show a shortcut without binding it. If INTEGRATION's manual test finds a platform where
   one key press runs a command twice, the flag is the fallback, applied through §5.

## 8. Open questions for the owner

1. **A JSON editor for `*.kenv.json`.** Phase 1 opens descriptors in the system's default application (decision 14);
   should a later phase add a JSON document kind with highlighting and in-editor descriptor diagnostics?
2. **Implicit contexts next to descriptors** (decision 13 above): directory-level coverage was chosen; the alternative
   is that every karpfen file not named by any descriptor joins an implicit context of its directory.
3. **dsl-tools console output in the IDE.** The converters `println` while parsing; the CLI redirects `System.out`, the
   IDE currently lets it reach the console. Redirect it to a logger in the app as well, or wait for dsl-tools to adopt
   SLF4J (proposal §5.1 item 6)?

The former question "menu accelerators vs. editor keymap" is resolved by decision 15.
