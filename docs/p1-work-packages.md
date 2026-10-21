# P1 — Work packages

Status: v1.3, 2026-10-20 (contracts step; v1.1 applied the first contract review, v1.2 the second one, v1.3 the third
one, see [§6](#6-decisions-of-the-contracts-step-that-refine-the-spec) and [§7](#7-review-findings-not-adopted)). Companion to
[p1-text-editing-spec.md](p1-text-editing-spec.md) (the
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
(`src/test/kotlin/<same package path>`) and their sub-packages. New files go into owned packages only. An owner may
create sub-packages of a package it owns (e.g. `ui.editor.state.highlight`, `ui.editor.view.render`) without the change
protocol (decision 37): every architecture rule covers sub-packages (rules match `pkg..`), and a sub-package must not
form a package cycle with its parent (so a collaborator in `state.highlight` does not depend on `state` itself while
`state` uses it). A WP may add public API to its *own* module (new types, new functions, new members with default
implementations); it never changes another WP's files.

Notation: paths are relative to the repository root; `…/` abbreviates `src/main/kotlin/io/karpfen/ide/` of the module
in the heading.

## 2. Rules for every work package

1. Branch/worktree per WP; commits end with the trailer line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
   Never run Docker or the karpfen runtime; never modify karpfen-dsl-tools or karpfen-runtime.
2. `JAVA_HOME=<JDK 21> ./gradlew build` is green at every merge, in artifact mode (composite mode is checked by CI).
   That includes `verifyModuleDependencies` and `architecture-tests`: ANTLR/converter confinement (imports and
   qualified references; inside core-document only `registry` may depend on the `antlr` and `dsl` adapter packages);
   Compose-, AWT- and Swing-free packages (`ComposeFreeStateTest`: `ui.editor.model..`, `ui.editor.state..`,
   `ui.foundation.command`, `ui.foundation.keys`, and every ui-workbench package except the root package, the
   features' `view` packages and `files.desktop`); no package cycles in core and UI modules; UI layering
   (`UiLayeringTest`: the editor model sees no other editor package, editor `view..` and `popup..` meet only through
   `overlay`, editor `state..` sees no view/popup/overlay/theme, foundation `keys`/`command` see no `keys.compose`,
   Compose-free workbench packages see no view package, the root package or `files.desktop`, the feature sub-states
   see no mediator package `state`, ports in `files` see no adapters); the P0 layer rules. Compose-side helper state
   (e.g. a hoisted `TreeViewState` for a widget) belongs in view packages; a new *top-level* package in ui-editor or
   ui-foundation is a contract change (§5), a sub-package of an owned package is not (§1); in ui-workbench a new
   feature follows the by-feature layout (decision 31).
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
   (`testImplementation`/`testRuntimeOnly`). All production dependencies are in place. A test-only library goes
   through the version catalog like every dependency: a WP may **append** test-only entries (version + library alias)
   to `gradle/libs.versions.toml`; it never changes or removes existing entries, and raw coordinates in build files
   are not allowed. WPs sharing a module (TOKENS + PARSE in core-document; EDITOR-MODEL + EDITOR-VIEW + EDITOR-POPUPS
   in ui-editor) or the catalog add lines only; the later merge resolves the trivial conflict.
8. Shared test fixtures (`core/core-document/src/testFixtures`) are CONTRACT. Use them through
   `testImplementation(testFixtures(project(":core:core-document")))` (already declared in every module that needs
   them): `Examples.files()`, `Examples.contexts()`, `Examples.copyTo(dir)`, `Mutations.random(text, seed, count)`,
   `Mutations.prefixes(text)`, `SyntheticKstates(n)` / `SyntheticKstates.withMinLines(n)`, and the IDE-authored
   environment descriptors `DescriptorExamples.text(name)` / `DescriptorExamples.copyExplicitContext(dir)` (valid,
   unknown key + missing file, invalid JSON; expected findings in `ide-examples/README.md`). Shared, end-to-end and
   CLI scenarios use these descriptors and invent none. Unit tests of the descriptor reader and the context resolver
   (WP-WORKSPACE) may write descriptor text or build `EnvironmentDescriptor`/`DescriptorReadResult` values in their own
   test sources for rules the shared files do not cover (a missing required key, paths into sub-directories and `..`,
   one file named by two descriptors, two descriptors in one directory).
9. The phase-0 `<Name>Module` stub objects and their tests stay untouched.
10. Logging configuration: `logback.xml` exists only in `app/ide/src/main/resources` and `tools/cli/src/main/resources`
    (WP-APP). The test classpaths of `app/ide` and `tools/cli` have a console-only `src/test/resources/logback-test.xml`
    (CONTRACT since v1.3, so no WP can forget it), which takes precedence over `logback.xml`; tests never write into a
    real log directory.

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

WP-APP compiles against the workbench API (CONTRACT) but cannot run the application before WP-WORKBENCH is merged:
it tests `IdeApplication.prepare` headless with a fake `WorkbenchState` (an interface), the menus and the crash
handling; the launch smoke test (spec §11 item 6) and the first run of the real window are verified by INTEGRATION,
which may fix defects in owned files of merged WPs (§5 rule 7). Likewise `CodeEditor` and `EditorPopups` first run
together in INTEGRATION's `ui.editor.e2e`.

## 3. Contract files (frozen)

Changes only through [§5](#5-contract-change-protocol). Owners of the surrounding module may *read* and *use* them.

core/core-text (`…/core/text/`): `TextRange.kt`, `TextPosition.kt`, `TextEdit.kt`, `Bias.kt`, `CaretMemento.kt`,
`EditOrigin.kt`, `TextChange.kt`, `UndoResult.kt`, `TextSnapshot.kt`, `TextLineage.kt`, `UndoManager.kt`,
`EditableText.kt` (incl. the complete `mapRange`, decision 35).

core/core-document (`…/core/document/`): `KarpfenLanguage.kt`, `Diagnostic.kt`, `syntax/TokenKind.kt`,
`syntax/SyntaxToken.kt`, `syntax/TokenizeResult.kt`, `syntax/Tokenizer.kt`, `parse/SymbolKind.kt`,
`parse/SemanticSpan.kt`, `parse/OutlineNode.kt`, `parse/Symbols.kt`, `parse/SyntaxIndex.kt`, `parse/DslModel.kt`,
`parse/ParseOutcome.kt`, `parse/DocumentParser.kt`, `language/LanguageSupport.kt`, `language/LanguageRegistry.kt`,
`registry/DefaultLanguageRegistry.kt`, `registry/KarpfenLanguageSupports.kt`, `dsl/DslConverter.kt`,
`dsl/DslErrorLocator.kt`, `session/DocumentId.kt`, `session/DocumentSession.kt` (incl. `ExternalDiagnosticSources`),
`session/DocumentSessionConfig.kt`, `session/VersionedDiagnostics.kt`, `session/DiagnosticsMerge.kt` (the one merge
rule `mergeDiagnostics`, complete); `DocumentSession.tokensFor` is complete (decision 38).
**Shared ANTLR mechanics** of WP-TOKENS and WP-PARSE, complete and tested in the contracts step (decision 16):
`antlr/CodePointOffsets.kt`, `antlr/IdeErrorListener.kt`, `antlr/KarpfenLexers.kt`, `antlr/LexerGaps.kt`,
`antlr/EvalBodies.kt`. The test fixtures `src/testFixtures/**` (incl. `ide-examples/**` and `DescriptorExamples.kt`)
and the tests `src/test/kotlin/io/karpfen/ide/core/document/fixtures/ExamplesFixtureTest.kt`, `…/dsl/DslToolsSmokeTest.kt`,
`…/dsl/FixtureConversionTest.kt`, `…/antlr/CodePointOffsetsTest.kt`, `…/antlr/IdeErrorListenerTest.kt`,
`…/antlr/LexerGapsTest.kt`, `…/antlr/EvalBodiesTest.kt`.

core/core-workspace (`…/core/workspace/`): `Workspace.kt` (incl. `WorkspaceFactory`), `WorkspaceOptions.kt`,
`files/FileKind.kt`,
`files/WorkspaceTree.kt`, `files/FileWatcher.kt`, `document/DocumentManager.kt`, `document/OpenDocument.kt`,
`document/TextFileStore.kt`, `document/SourceLoader.kt`, `environment/EnvironmentDescriptor.kt`,
`environment/EnvironmentDescriptorReader.kt`, `context/ModelingContext.kt`, `context/ContextResolution.kt`,
`context/ContextResolver.kt`, `analysis/ProjectAnalysis.kt`, `analysis/ProjectAnalyzer.kt`,
`problems/WorkspaceProblems.kt`, `diagnostics/WorkspaceDiagnosticCodes.kt` (v1.3).

core/core-lang (`…/core/lang/`): `LanguageServices.kt`, `LanguageDocument.kt` (incl. `Location`), `Highlighting.kt`
(incl. `HighlightStyle.isSemantic` and the complete `overlayHighlights`, decision 39), `Completion.kt`, and the *signatures* of `defaultLanguageServices(…)` (with `ProviderFailure`) and
`karpfenLanguageFeatures()` in `services/LanguageServicesFactory.kt` (the file itself is owned by WP-LANG, see below).

ui/ui-foundation (`…/ui/foundation/`): `keys/KeyStroke.kt` (incl. the punctuation rule on `KeyCode`, decision 41),
`command/Command.kt`.

ui/ui-editor (`…/ui/editor/`): `model/caret/Caret.kt`, `model/caret/TabStops.kt` (complete, incl. `DEFAULT_TAB_WIDTH`
and `displayColumns`; decisions 40 and 61), `model/action/EditorAction.kt` (incl. `EditorActionContext`, `EditorActionExecutor`, `EditorUiPort`),
`model/action/EditorActionIds.kt` (incl. `EditorActionTitles`), `model/navigation/GoToLineQuery.kt`,
`state/CodeEditorState.kt` (incl. the role interfaces `EditorHandle` and `EditorViewModel`, decision 36),
`state/EditorPopupModel.kt` (what the popups may use), `state/EditorOverlayState.kt` (complete; shared by VIEW and
POPUPS), `overlay/EditorOverlays.kt` (the slot API between VIEW and POPUPS; `isCaretScrolledAway`, decision 59).

ui/ui-workbench (`…/ui/workbench/`): `state/WorkbenchState.kt`, `state/WorkbenchServices.kt`,
`command/WorkbenchCommandIds.kt`, `settings/IdeSettings.kt`, `settings/SettingsRepository.kt` (incl.
`SettingsLocations`), `files/ProjectFileOperations.kt` (the ports `ProjectFileOperations`, `DeleteOutcome`,
`FolderChooser`), and the *signature* of `defaultWorkbenchServices(…)` in `DefaultWorkbenchServices.kt` and of
`Workbench(…)` in `Workbench.kt`. The *public members as declared* of the sub-states in their feature packages
(`projecttree/ProjectTreeState.kt`, `tabs/EditorTabsState.kt`, `outline/OutlineState.kt`, `problems/ProblemsState.kt`,
`statusbar/StatusBarState.kt`, `dialog/DialogState.kt`, `layout/LayoutState.kt`) and of `templates/FileTemplates.kt`
are part of the contract, and their value types (`EditorTab`, `OutlineUiState`, `ProblemItem`, `ProblemGroup`,
`ProblemFilter`, `ProblemsUiState`, `StatusBarUiState`, `DialogRequest`/`DialogResponse` and the answer interfaces,
`ToolWindowId`, `LayoutUiState`, `FileTemplateKind`, `TemplateContext`, `TemplateObject`) are complete; other WPs may
use them (e.g.
WP-APP `dialogs.ask(DialogRequest.Error(…))`). WP-WORKBENCH owns the files and may add members; the sub-states'
internal members (mediator-only operations) may change.

app/ide (`…/app/`): the *signatures* of `IdeApplication(…)`, `IdeApplication.prepare`, `IdeApplication.run` and the
class `IdeSession` in `IdeApplication.kt` (the file is owned by WP-APP); `app/ide/src/test/resources/logback-test.xml`.

tools/cli (`…/tools/cli/`): `validate/ValidationReport.kt`, the *signatures* of `ValidateCommand(…)` and
`StandardOutputGuard(…)` (owned by WP-APP); `tools/cli/src/test/resources/logback-test.xml`.

architecture-tests: `KarpfenModules.kt`, `ForeignApiConfinementTest.kt`, `ComposeFreeStateTest.kt`,
`PackageCycleTest.kt`, `UiLayeringTest.kt` and the P0 tests.

Contract tests (they pin the complete values and wiring; extend coverage in your own test files, do not edit these):
`core/core-text/src/test/kotlin/io/karpfen/ide/core/text/TextValuesContractTest.kt`,
`core/core-text/src/test/kotlin/io/karpfen/ide/core/text/RangeMappingContractTest.kt`,
`core/core-document/src/test/kotlin/io/karpfen/ide/core/document/DocumentContractTest.kt`,
`core/core-workspace/src/test/kotlin/io/karpfen/ide/core/workspace/WorkspaceContractTest.kt`,
`core/core-lang/src/test/kotlin/io/karpfen/ide/core/lang/LanguageContractTest.kt`,
`ui/ui-foundation/src/test/kotlin/io/karpfen/ide/ui/foundation/keys/KeysContractTest.kt`,
`ui/ui-editor/src/test/kotlin/io/karpfen/ide/ui/editor/EditorContractTest.kt`,
`ui/ui-workbench/src/test/kotlin/io/karpfen/ide/ui/workbench/WorkbenchContractTest.kt`.

Build files: all `build.gradle.kts` (except test-only additions, rule 7), `gradle/libs.versions.toml` (except appended
test-only entries, rule 7), `build-logic/**`.

## 4. Work packages

### WP-TEXT — core-text

- **Owns**: package `io.karpfen.ide.core.text` (main and test) except the CONTRACT files. Skeletons
  (`core/core-text/…/core/text/`): `DefaultEditableText.kt`, `DefaultUndoManager.kt`, `LineIndex.kt` (bodies),
  `LineSeparator.kt` (the `LineSeparators` bodies; `LineSeparator` and `NormalizedText` are complete). New internal
  files (edit log, offset mapping, coalescing policy) go into the same package.
- **Implements**: `EditableText` (via `DefaultEditableText`; every snapshot carries the instance's one `TextLineage`;
  the bounded edit log is published as an immutable value together with each snapshot, so `mapOffset` — and the
  contract's `mapRange` on top of it — is thread-safe while the single writer edits), `UndoManager`, `LineIndex`,
  `LineSeparators`. `mapRange` itself is complete in the CONTRACT file (decision 35); do not override it.
- **Fakes**: none needed (no dependencies). Time: `kotlin.time.TestTimeSource`.
- **Must satisfy**: spec §1 (offsets, separators, threading, determinism), §2 all MUST rules. Acceptance tests (spec §2
  Tests): ≥ 10,000 random ops per seed against a `StringBuilder` reference; undo-all returns the initial text,
  redo-all the final one; coalescing rules (typing, deletions, newline/whitespace breaks, 1000 ms window,
  Paste/Cut/Command/External never coalesce, `group`, `breakCoalescing`); caret mementos of merged steps (a group or
  coalesced run restores the first change's `caretsBefore` on undo and the last change's `caretsAfter` on redo, see
  `UndoManager`); `isAtSavePoint` through undo/redo; `mapOffset` with both biases against a reference and `null` after
  eviction; `mapRange` on the real text against the `StringBuilder` reference with random ranges (start RIGHT, end LEFT,
  inverted → empty at the mapped end, empty ranges stay empty; null for another lineage, a newer version and evicted
  history; never throws); `mapOffset`/`mapRange` called from a second thread while the writer applies edits never
  throw and return values consistent with some published version; `LineIndex` edge cases (empty text, trailing newline, only newlines, very long line); `LineSeparators`
  round trip and mixed detection; `apply` rejects overlapping edits and returns null for no-ops; all snapshots of one
  text share its `lineage`, two texts have different ones.
- **Must not touch**: any other module.

### WP-FOUNDATION — ui-foundation

- **Owns**: all of `io.karpfen.ide.ui.foundation` except CONTRACT files. Skeletons (`ui/ui-foundation/…/ui/foundation/`):
  `keys/KeyShortcutText.kt`, `keys/compose/ComposeKeys.kt`, `command/CommandRegistry.kt`, `theme/KarpfenTheme.kt`,
  `icons/KarpfenIcons.kt`, `layout/SplitPane.kt` (`SplitPane`, `ToolWindow`), `widgets/TabStrip.kt`,
  `widgets/TreeView.kt`, `widgets/StatusBar.kt`.
- **Implements**: `CommandRegistry` (`DefaultCommandRegistry`), `KeyShortcut.displayText` (pure Kotlin in `keys`),
  `KeyEvent.toKeyStroke()` (layout-aware punctuation as documented on it and on `KeyCode`, decision 41) and
  `KeyShortcut.toMenuShortcut` (the Compose adapters in `keys.compose`; WP-APP binds menu accelerators with the latter,
  decision 15), palettes/typography, `KarpfenTheme`, icons (`ImageVector.Builder`, no material-icons-extended), the
  widgets (`ContextMenuAction` and `StatusBarItem` are plain classes holding behaviour, decision 33; `SplitPane` is
  size-based with a hoisted `fixedSize` of the `fixedSide` pane, decision 46; `TreeView` scrolls the selected row into
  view whenever `selected` changes). Hoisted widget state (e.g. a `TreeViewState`) may use Compose;
  `ui.foundation.command` and `ui.foundation.keys` must stay Compose-free (rule 2).
- **Fakes**: none needed.
- **Must satisfy**: spec §6 (with the size-based `SplitPane` of decision 46). Tests: registry
  register/replace/unregister/lookup by stroke per platform, disabled commands do not execute; shortcut display per
  platform; key-event mapping (headless `runComposeUiTest` with key input), including synthesized layout cases: German
  Shift+7 producing `/` → `SLASH` without SHIFT, Cmd+Shift+7 → `SLASH`+META, US Shift+/ → `SLASH`+SHIFT; headless UI
  tests for SplitPane drag + min sizes + a container resize keeping `fixedSize` (FIRST and SECOND side), TabStrip
  select/close/middle-click/dirty dot/overflow scroll, TreeView expand/collapse/selection/keyboard
  Up/Down/Left/Right/Enter/double-click and a programmatic `selected` change scrolling an off-screen row into view,
  StatusBar items.
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
  WARNING), and the bodies' content ranges as `TokenizeResult.embeddedRegions` (decision 27); `EVENT` as OPERATION in
  action blocks, KEYWORD in CONDITION blocks (block structure on brace tokens).
  `DocumentSession` (tokens via mapLatest without debounce, initial tokens synchronous; debounced, cancellable parse
  with the initial parse undebounced; `lastGoodParse`; `VersionedDiagnostics` with the newest snapshot, every source
  mapped to it with `EditableText.mapRange` — never a range mapping of its own (decision 35) — and merged by
  `mergeDiagnostics` — never a merge rule of its own (decision 28); `setExternalDiagnostics` thread-safe (any thread),
  ignoring snapshots of another lineage (`mapRange` answers null for them); `tokensFor` is complete in the contract and
  may only be overridden with the same semantics; `close`). Keep the no-arg constructors (used by
  `registry/KarpfenLanguageSupports.kt`) and the factory semantics. The default `DocumentSessionConfig` is
  `SYNTAX_ONLY` (decision 46); tests of the standalone pipeline pass `SemanticMode.STANDALONE` explicitly.
- **Fakes**: `DocumentParser` is WP-PARSE: build sessions with `LanguageSupport(language, realTokenizer, fakeParser)`
  where the fake returns hand-built `ParseOutcome`s (also with syntax diagnostics, to test `lastGoodParse`).
  Time: `StandardTestDispatcher` in `DocumentSessionConfig` + `runTest` virtual time.
- **Must satisfy**: spec §3.1 (all MUST rules), §3.2, §3.4 (flows, debounce, merging) and the tokenize budget
  (5,000-line KStates < 30 ms, asserted with 5x slack, measured value reported; use `SyntheticKstates.withMinLines(5000)`),
  §11 item 2 (zero LEXER diagnostics for every example) and item 3 for tokenizers (never throw on
  `Mutations.random(…, count = 10_000)` over the examples and on every prefix of one KStates example; token invariants
  hold on all of them). Rule from §3.1: every non-whitespace character in exactly one token — test it on all mutations.
  `embeddedRegions` equal the `EvalBody.content` ranges of `EvalBodies.find` on all mutations, and every EMBEDDED_*,
  PATH_REFERENCE and MACRO_REFERENCE token lies in one of them. Session: an external set for a snapshot of another
  lineage is ignored; the merged diagnostics equal `mergeDiagnostics` of the sources mapped with `mapRange`; typing at
  the offset of an empty diagnostic (missing `}` at end of file) and replacing a selection around a diagnostic never
  throw and keep the diagnostic as an empty range; `tokensFor` of a lagging session equals a fresh tokenization.
- **Must not touch**: `antlr/tree`, `dsl`, `parse`, `registry`, `language`, the root package, the shared files in the
  `antlr` root package (`CodePointOffsets.kt`, `IdeErrorListener.kt`, `KarpfenLexers.kt`, `LexerGaps.kt`,
  `EvalBodies.kt`), the CONTRACT files of `session` (incl. `DiagnosticsMerge.kt`), fixtures.

### WP-PARSE — core-document: ANTLR parse adapter, tree walkers, dsl-tools adapter

- **Owns**: packages `io.karpfen.ide.core.document.antlr.tree` and `io.karpfen.ide.core.document.dsl` (except the
  CONTRACT files `dsl/DslConverter.kt`, `dsl/DslErrorLocator.kt`), and the owned file `parse/ParserSeams.kt`.
  Skeletons (`core/core-document/…/core/document/`): `antlr/tree/KmetaSyntaxAnalyzer.kt`,
  `antlr/tree/KmodelSyntaxAnalyzer.kt`, `antlr/tree/KstatesSyntaxAnalyzer.kt`, `dsl/DefaultDslConverter.kt`,
  `dsl/QuotedNameErrorLocator.kt`, `dsl/StandaloneSemanticsAdapters.kt`, `parse/ParserSeams.kt`
  (`ComposedDocumentParser` and the internal seams `SyntaxAnalyzer`, `SyntaxAnalysis`, `StandaloneSemantics`,
  `StandaloneResult`, which WP-PARSE may refine). Tests in `…/core/document/antlr/tree/`, `…/dsl/` (new files only),
  `…/parse/`.
- **Implements** (note: `DocumentParser.parse` and `DocumentSessionConfig` default to `SYNTAX_ONLY` since v1.3; tests of
  the standalone semantics request `SemanticMode.STANDALONE` explicitly): `SyntaxAnalyzer` ×3 (`CommonTokenStream` over
  `KarpfenLexers.create`, one `IdeErrorListener`
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
  word/line/all, version-stamped carets caught up lazily on every member (`current`, `primary`, `mementos`, every move)
  and followed in `state` for observers, preferred column in display columns with `TabStops(tabWidth())` — never a tab
  rule of its own (decision 40) —, `breakCoalescing()` on every explicit move), `AutoClosedPairs` (version-stamped,
  mapped on query), every action of `EditorActionIds` (one undo step each, caret mementos before/after; `isEnabled`
  reads neither clipboard nor tokens, so Paste is always enabled and returns false on an empty clipboard; FIND_NEXT /
  FIND_PREVIOUS forward to `EditorUiPort.findNext`/`findPrevious`), `typeTextAction` (auto-close and over-type of
  *tracked* closers only), `EditorActionRegistry`, `defaultEditorKeymap` (bindings listed on `EditorKeymap`, incl.
  find next/previous), `FindModel` (matches on the injected background dispatcher, published in its scope; operations
  wait for the published matches when they lag, decision 54; `setQuery` selects the first match at or after the selection start,
  `next`/`previous` first select the current match if it is not selected yet — every selection through an internal
  select-match `EditorAction` run by the executor, decision 37) and `CompletionSessionModel` (triggers, filtering and
  ranking, close rules, `handleKey`; observes `caret.state` itself; requests the suspending, versioned
  `CompletionSource` in coroutines of its scope, a newer request cancelling an older one, stale results dropped; it
  never names a dispatcher, decision 30), both editing only through internal replace/accept `EditorAction`s run by
  their `EditorActionExecutor`. Toggle comment decides `#` vs `//` per line with `context.tokens.embeddedRegionAt`, as
  documented on `ToggleCommentAction` (decision 27); the context's tokens belong to the current text (decision 38).
  Actions never scroll (the editor state reveals the caret).
- **Fakes**: `EditorActionContext`, `ClipboardPort`, `EditorUiPort`, `CompletionSource` (hand-written fakes, the source
  e.g. suspending until the test releases it); an `EditorActionExecutor` that runs the action in the fake context; real
  `AutoClosedPairs`; tokens as hand-built `TokenizeResult`s with explicit `embeddedRegions` for EVAL bodies; `KeyStroke`s
  built directly (values are complete). Real `EditableText` (WP-TEXT merged). Coroutines: `StandardTestDispatcher` or
  `UnconfinedTestDispatcher` as scope and as find's background dispatcher (virtual time, no real threads).
- **Must satisfy**: spec §7.1 (all bullets), "every action is exactly one undo step", undo/redo restore carets (rule of
  `UndoManager`). Tests per action group, per movement (incl. Up/Down over tab lines with tab widths 2 and 4), per find
  option (invalid regex → error state), completion ranking/stability, a typed `)` before a user-typed `)` is inserted
  (not over-typed), Backspace inside a user-typed `()` deletes one character; toggle comment on an empty line, a
  `// note` line and the closing-brace line inside a multi-line EVAL body uses `#`, on the `EVAL {` line `//`;
  completion results for an older version are dropped and re-requested. Also: with a `StandardTestDispatcher` scope, an
  `External` change applied before the caret collector ran is seen by the next action (carets caught up, no stale
  offsets, no exception); find: `setQuery` selects the first match at or after the selection start, the first `next()`
  after it moves to the following match, `next()` with an unselected current match selects that one, and every
  selection reaches the executor (a recording executor sees a select action); Paste is enabled with an empty clipboard
  and its `execute` returns false.
- **Must not touch**: `state`, `view`, `theme`, `overlay`, `popup`; CONTRACT files.

### WP-WORKSPACE — core-workspace

- **Owns**: all of `io.karpfen.ide.core.workspace` except CONTRACT files. Skeletons
  (`core/core-workspace/…/core/workspace/`): `DefaultWorkspace.kt` (incl. `WorkspaceCollaborators`),
  `files/WatchServiceFileWatcher.kt`, `files/WorkspaceScanner.kt`, `document/DefaultDocumentManager.kt` (incl. the
  internal `WorkspaceDocuments` interface the workspace depends on), `document/AtomicTextFileStore.kt`,
  `document/DefaultSourceLoader.kt`, `environment/JsonEnvironmentDescriptorReader.kt`,
  `context/DefaultContextResolver.kt`, `analysis/DefaultProjectAnalyzer.kt`, `problems/WorkspaceProblemsAggregator.kt`.
  The wiring in the `Workspace(…)` and `WorkspaceFactory(…)` factories (CONTRACT `Workspace.kt`) is complete.
- **Implements**: `Workspace` (scan, watch + 200 ms debounce, refresh, contexts incl. loose files, debounced 300 ms
  cancellable analysis, `isAnalyzing`, `awaitAnalysis` returning once `analysis` and `problems` reflect it, pushing
  `ExternalDiagnosticSources.PROJECT` diagnostics into open sessions with the `AnalyzedFile.snapshot`, `problems` by
  the single rule documented on `WorkspaceProblems`) as a coordinator over internal collaborators (tree refresher,
  analysis scheduler, diagnostics publisher, problems aggregator); every collaborator — the `WorkspaceDocuments`
  (`DefaultDocumentManager`) and the `WorkspaceScanner` (an internal interface; `FileSystemWorkspaceScanner` walks the
  disk) included — comes through `WorkspaceCollaborators` (a plain class) from the `Workspace(…)` factory, never
  created inside; the coordinator builds its internal pipeline objects (tree refresher, analysis scheduler, diagnostics
  publisher, problems aggregator) itself from those collaborators, its scope and options — they are its structure, not
  seams. The watch pipeline talks to `WorkspaceDocuments.onFileChanged`, not to the class. `FileWatcher` (recursive `WatchService`). `SourceLoader` (UTF-8 with replacement + WARNING on the first
  U+FFFD, BOM removed and flagged, separators normalized + INFO on the first minority line break). `TextFileStore`
  (BOM-aware read/write). `DocumentManager`/`OpenDocument` (loading through the `SourceLoader`, findings as external
  source `FILE` — cleared by a successful save, replaced by every reload; `WorkspaceOptions.timeSource` for every
  `EditableText`, dirty flag from the save point, atomic save restoring separators and BOM, self-induced event
  suppression, reload as one `External` change when clean, conflict when dirty or deleted, `resolveConflict` returning
  a `ConflictResult` and never throwing (decision 43), **every** session `SYNTAX_ONLY` (decision 28); every text
  mutation on the workspace scope's dispatcher). Codes of workspace findings come from `WorkspaceDiagnosticCodes`. `EnvironmentDescriptorReader`
  (on normalized text; unknown keys → WARNING, JSON value ranges). `ContextResolver` (rules on the interface;
  `looseFiles` = every karpfen source in no context, the metamodels an implicit context did not choose included,
  decision 29). `ProjectAnalyzer` (through `DslConverter` only — never dsl-tools directly, enforced by architecture
  tests; converting only syntax-clean files and skipping conversions that need an unconverted metamodel, as documented
  on `ProjectAnalyzer` (decision 44); `AnalyzedFile` with lexical and file diagnostics for context and loose files; the
  conventions documented on `ProjectAnalysis`: normalized paths, session snapshots for open documents with tokens from
  `DocumentSession.tokensFor`, version 0 with a fresh lineage for disk files, syntax-only parses).
- **Fakes**: a `FileWatcher` fake that emits `FileChange`s on demand (the JDK's macOS watch service polls slowly; at most
  one real-watcher test, tagged slow-tolerant with generous timeouts); optionally an in-memory `TextFileStore` or
  `SourceLoader`. Real tokenizers/parsers/`DslConverter` (WP-TOKENS, WP-PARSE merged). Virtual time via test dispatchers
  and a `TestTimeSource` in `WorkspaceOptions`. Real folders via `Examples.copyTo(@TempDir)` and
  `DescriptorExamples.copyExplicitContext(@TempDir)`.
- **Must satisfy**: spec §4 (all bullets) and §11 item 2 (every example context analyzes with zero DSL_TOOLS errors).
  Tests: tree order and ignore rules; FileKind; descriptor reading over `DescriptorExamples` (valid, invalid JSON,
  unknown key, missing file with range) plus test-local descriptors (rule 8: missing required key, sub-directory and
  `..` paths, a file in two descriptors, two descriptors in one directory); explicit and implicit contexts incl. loose
  files; two metamodels `a.kmeta` and `b.kmeta` in one directory with `b` missing a `}`: `b` is a loose file and its
  PARSER error is in `problems` (as the CLI reports it), next to the INFO `context-multiple-metamodels`; analysis of
  every example context; the problems rule (open document, unopened context file, loose file, descriptor), and that
  opening and closing a loose or a context file leaves `problems` unchanged once the analysis caught up; external-change
  reload and conflict; RELOAD of a file deleted on disk returns `DeletedOnDisk` and changes nothing; a save clears the
  FILE findings and a reload replaces them; a PROJECT push of an analysis started before a close and reopen is ignored;
  save round trip with CRLF/CR files and with a BOM; malformed UTF-8. A model (and a metamodel) with a syntax error
  yields its PARSER errors and no DSL_TOOLS diagnostic, the models of a broken metamodel are not converted, and the
  analysis of such a copy writes nothing to `System.err` (capture it in the test).
- **Must not touch**: other modules; CONTRACT files.

### WP-LANG — core-lang

- **Owns**: all of `io.karpfen.ide.core.lang` except the CONTRACT root files (`LanguageServices.kt`,
  `LanguageDocument.kt`, `Highlighting.kt`, `Completion.kt`). Skeletons (`core/core-lang/…/core/lang/`):
  `feature/LanguageFeatures.kt` (SPI with `FeatureRequest`, may be extended), `context/CaretContext.kt`,
  `symbols/TypeCatalog.kt` (values complete, `ContextTypeCatalog` to implement), `modelpath/ModelPathResolver.kt`
  (values complete: `PathMember` wraps `MemberInfo`), `highlight/DefaultHighlightProvider.kt`,
  `brackets/TokenBracketMatcher.kt`, `kmeta/Kmeta{LanguageFeatures,CompletionProvider,NavigationProvider,
  HoverProvider}.kt`, the same four files in `kmodel/` (`Kmodel…`) and `kstates/` (`Kstates…`) plus
  `kstates/KstatesPathScopes.kt`, `services/DefaultLanguageServices.kt`, `services/LanguageServicesFactory.kt` (keep
  the two public signatures and `ProviderFailure`). The shared mechanics (`CaretContextDetector`, `TypeCatalog`,
  `ModelPathResolver`, `KstatesPathScopes`) and their value types may be refined. `DefaultHighlightProvider` overlays with
  the contract's `overlayHighlights` (decision 39), never an overlay of its own.
- **Implements**: `LanguageServices` facade (delegation per language, never throws, logs provider failures and reports
  them to `onProviderFailure`), highlighting (lexical + semantic overlay when versions match), per-language
  completion/navigation/hover per spec §5, bracket matching, and the shared collaborators: caret-context detection
  (`embedded` exactly from `tokens.embeddedRegionAt`, decision 27), `TypeCatalog` (types of the document and the
  context with members and declaration `Location`s, converted once per `ContextAnalysis` and memoized; hover texts from
  `TypeInfo.hoverText`/`MemberInfo.signature`; event types are the TYPE declarations of the events file, not all of
  `ContextAnalysis.events.types`, which contains the domain base types too), model-path resolution over `TypeInfo`, the KStates path scope at an
  offset (`KstatesPathScopes`, one collaborator for completion, hover and navigation). Every provider follows the
  staleness rule on `LanguageDocument` and fills `Location.version` and `Location.lineage` from the outcome the range
  came from (current parse, last good parse, analyzed snapshot; decision 26).
- **Fakes**: none for code; project knowledge is built directly: `ProjectAnalysis`/`ContextAnalysis`/`AnalyzedFile`/
  `ModelingContext` are complete values, metamodels/models/state machines come from `DslConverter()` (WP-PARSE) over
  `Examples.contexts()`, parse outcomes from `defaultLanguageRegistry()` with `SemanticMode.SYNTAX_ONLY`. Build them by
  the conventions documented on `ProjectAnalysis` (normalized absolute paths, `TextSnapshot(text, 0)` per disk file,
  syntax-only parses), so they look like the real analyzer's. Pass `MutableStateFlow(analysis)` to
  `defaultLanguageServices`. Do not use WP-WORKSPACE's analyzer.
- **Must satisfy**: spec §5 (completion by context for all three languages, definition/references, hover, matching
  bracket, structure with per-language strategies and shared collaborators) and §11 item 3 for highlight, completion,
  definition and hover (never throw on 10,000 mutations of the examples and every prefix of one KStates example). The
  robustness tests call `defaultLanguageServices(…, onProviderFailure = recorder)` and assert the recorder stays empty
  (the facade's catch-all must not hide provider exceptions). Event names in `EVENT("d", "` and the event types of the
  catalog contain no domain type (single_robot_example: no `Robot`, `Vector`). Definition of a state reference while the file is broken
  (declaration only in the last good parse) returns that parse's version, so navigation after edits above the target
  lands on the declaration.
- **Must not touch**: other modules; the CONTRACT root files.

### WP-EDITOR-VIEW — ui-editor view, state, theme

- **Owns**: packages `io.karpfen.ide.ui.editor.state` (except CONTRACT files `CodeEditorState.kt`,
  `EditorPopupModel.kt`, `EditorOverlayState.kt`), `io.karpfen.ide.ui.editor.view`, `io.karpfen.ide.ui.editor.theme`,
  with their sub-packages (§1: e.g. `state.highlight`, `state.input`, `view.render`; no cycle with the parent).
  Skeletons (`ui/ui-editor/…/ui/editor/`): `state/DefaultCodeEditorState.kt`, `state/EditorViewport.kt`,
  `view/CodeEditor.kt`, `view/EditorStateFactories.kt` (`rememberCodeEditorState`, `systemClipboard`),
  `view/LineLayoutCache.kt`, `theme/EditorTheme.kt` (palettes and `LocalEditorTheme`; the data class is frozen).
- **Implements**: `CodeEditorState` — all three roles `EditorViewModel`, `EditorPopupModel`, `EditorHandle` (decision
  36) — as a coordinator over internal collaborators of the `state` package or its sub-packages (action-context adapter
  implementing `EditorActionContext`/`EditorUiPort` with one `AutoClosedPairs` per editor, `tokens` read lazily as
  `session.tokensFor(text.snapshot.value)` (decision 38), `findNext`/`findPrevious` through the `FindModel` (opening the
  find bar while the query is empty) — `openFind` may seed the query from a one-line selection through
  `FindModel.setQuery` before `overlays.openFind`; completion source implementing the suspending `CompletionSource` with
  `withContext(backgroundDispatcher)` and `LanguageDocument.of(session, snapshot)`; `FindModel` created with
  `backgroundDispatcher`; highlight pipeline (with the semantic carry-over documented on `EditorViewModel.highlights`,
  decision 39) and bracket tracker on `backgroundDispatcher`; hover/definition resolver waiting (bounded) for a current
  parse (internal; `hoverAt`/`languageDocument` left the public interface); the input intents `handleKey` (completion
  first, then keymap; true exactly when handled), the pointer intents `pointerPressed`/`pointerDragged`/
  `pointerReleased` (click policy incl. the keymap platform's primary modifier for go to definition), `pointerHover`
  (600 ms `HOVER_DELAY` in the editor's scope), `openContextMenuAt` (caret moves to the click unless inside the
  selection) and `onFocusChanged`/`isFocused` (focus loss closes completion and the hover); the caret reveal after
  every `execute`/`type` that changed carets or text — find selections included, since they run through `execute`;
  `enabledActions`; pending `scrollRequest` and `focusRequested` with their consume functions; `goToLine`),
  `EditorViewport`, `CodeEditor` (rendering, virtualization, layout cache, gutter, decorations — find matches only
  while `overlays.findBar` is open; every range of an older version mapped with `EditableText.mapRange`, never by
  mapping bounds yourself (decision 35); tabs, hit-testing and caret x with `TabStops` (decision 40) — input incl.
  IME/dead keys/macOS Option combinations and `SemanticsActions.InsertTextAtCursor`, key events to `handleKey` consumed
  exactly when it returns true (decision 15), focus changes to `onFocusChanged`, mouse hit-testing plus the drag
  auto-scroll timer only, everything else to the state's intents with x/y in the `EditorOverlayHost` space,
  scrollbars, applying pending requests when composed, `onNavigate` only for simple embeddings), hosting
  `EditorOverlays.Header`/`Popups` with an `EditorOverlayHost` implementation, editor themes, the lazy system
  clipboard port with headless fallback (decisions 15 and 30).
- **Fakes**: a fake `LanguageServices` (fixed spans/items/locations); `EditorOverlays.None` or a test overlay that
  records host calls (POPUPS is developed in parallel). Real sessions via `DocumentSession(…)` with
  `defaultLanguageRegistry()` (TOKENS and PARSE merged), real models (EDITOR-MODEL merged), real foundation; a test
  dispatcher as `backgroundDispatcher` and in the editor's scope.
- **Must satisfy**: spec §7.2 (rendering, input, mouse, scrolling, `scrollTo`) and the headless benchmark (10,000-line
  KStates via `SyntheticKstates.withMinLines(10_000)`, scrolled to the middle, average frame time after warm-up
  < 50 ms, reported), §11 item 4 (headless Compose tests: typing incl. `performTextInput`, selection by mouse and keys,
  Cmd/Ctrl+click navigation, hover after 600 ms with diagnostics, scroll-to-caret). Unit tests (Compose-free, virtual
  time) of the input policy: `handleKey` routes to completion first and returns true exactly for handled strokes; the
  hover appears after `HOVER_DELAY` and is hidden on move/leave (after a grace period, kept while the pointer is on the
  tooltip, decision 56); a right-click outside the selection moves the caret,
  inside it keeps the selection; Page Down and arrow keys at the viewport edge issue a reveal `scrollRequest`; pointer
  gestures (single, double, triple, Shift and primary-modifier clicks per platform; drag by characters, words and
  lines); focus loss closes completion and the hover and updates `isFocused`; find next to an off-screen match (via
  `execute(FIND_NEXT)` and via `find.next()`) issues a reveal `scrollRequest`; a typed character leaves the semantic
  style of untouched names unchanged while the parse lags (carry-over); toggle comment right after Enter inside an EVAL
  body, before the tokenize pipeline caught up, uses `#` (the action context's tokens have the text's version). Also:
  `scrollTo(center)` and `requestFocus()` issued before the first composition take effect once `CodeEditor` is
  composed; a handled key stroke is consumed and an unhandled one is not; `enabledActions` follows selection and undo
  history.
- **Must not touch**: `model/**`, `popup/**`, `overlay/**`, CONTRACT files.

### WP-EDITOR-POPUPS — ui-editor popups

- **Owns**: package `io.karpfen.ide.ui.editor.popup`. Skeletons (`ui/ui-editor/…/ui/editor/popup/`): `EditorPopups.kt`,
  `CompletionPopup.kt`, `FindReplaceBar.kt`, `GoToLinePopup.kt`, `HoverTooltip.kt`, `EditorContextMenu.kt`.
- **Implements**: `EditorPopups : EditorOverlays` — `Header` (find/replace bar, focusing and selecting its query field
  whenever `overlays.findFocusRequests` changes; query changes call `find.setQuery`, Enter/Shift+Enter `find.next()` /
  `find.previous()` — the model selects through the editor's executor, so the editor reveals the match; the bar never
  scrolls itself) and `Popups` (completion list, go-to-line, hover tooltip, context menu
  at `ContextMenuRequest.x/y` in the host's coordinate space with the titles of `EditorActionTitles`), each composable
  rendering immutable state and calling the host's `EditorPopupModel` (go-to-line through `goToLine`, context-menu
  enablement through `isEnabled`, focus return through `requestFocus`).
- **Fakes**: a fake `EditorPopupModel` (the narrow interface, not `CodeEditorState`) wired to real models
  (`CaretModel`, `FindModel`, `CompletionSessionModel` from EDITOR-MODEL, with an `EditorActionExecutor` running the
  actions in a fake `EditorActionContext`) and a real `EditorOverlayState`; a fake `EditorOverlayHost` with fixed
  geometry; an `EditorTheme` built in the test (the palettes are WP-EDITOR-VIEW's). Do not call
  `CodeEditorState(…)`/`rememberCodeEditorState` (VIEW, not merged).
- **Must satisfy**: spec §7.2 Popups bullet: completion ≤ 12 rows with kind badge/label/detail; find bar with Aa/W/.*
  toggles, "n/m" counter, Enter/Shift+Enter, Escape returns focus (`requestFocus`), a second `openFind` in the same
  mode refocuses the field; go-to-line `line[:col]`; hover tooltip; context menu Cut/Copy/Paste/Go to
  Definition/Toggle Comment by action id, disabled entries greyed out. Headless tests per popup.
- **Must not touch**: everything outside `popup`.

### WP-WORKBENCH — ui-workbench

- **Owns**: all of `io.karpfen.ide.ui.workbench` except CONTRACT files and INTEGRATION's test package
  `io.karpfen.ide.ui.workbench.e2e`. The module is organized by feature (decision 31): each feature package holds its
  Compose-free sub-state, its `view` sub-package the composables. Skeletons (`ui/ui-workbench/…/ui/workbench/`):
  `Workbench.kt` (body), `DefaultWorkbenchServices.kt` (body), `state/DefaultWorkbenchState.kt` (the mediator),
  `projecttree/ProjectTreeState.kt`, `tabs/EditorTabsState.kt`, `outline/OutlineState.kt`, `problems/ProblemsState.kt`,
  `statusbar/StatusBarState.kt`, `dialog/DialogState.kt`, `layout/LayoutState.kt` (sub-states may get new members;
  their declared public members are frozen, their internal mediator operations are not, see §3),
  `settings/JsonSettingsRepository.kt`, `templates/FileTemplates.kt` (`DefaultFileTemplates`),
  `files/desktop/DesktopFileOperations.kt`, `projecttree/view/ProjectTreeView.kt`, `tabs/view/EditorArea.kt`,
  `outline/view/OutlineView.kt`, `problems/view/ProblemsView.kt`, `statusbar/view/WorkbenchStatusBar.kt`,
  `dialog/view/WorkbenchDialogs.kt`, `welcome/view/WelcomeView.kt`. New internal collaborators of the mediator go into
  `state`; new feature state into its feature package.
- **Implements**: `WorkbenchState` (Mediator) as a coordinator over internal collaborators of the `state` package
  (intent runner, command registrar, layout persister, editor-navigation collector, unsaved-changes flow,
  file-operations flow, template-context builder), using editors as `EditorHandle` (decision 36): non-suspending
  intents that launch their flow in the mediator's scope, one at a time, and return its `Job` (decision 42);
  `openFolder`, `openFile` (files the editor cannot open go to `openExternally`, decision 14), `navigateTo` (mapped
  with `EditableText.mapRange`, clamped when it returns null; decisions 26 and 35), the project-tree intents
  `createFile` (with a `TemplateContext` built from `Workspace.contexts` and the syntax indexes of `Workspace.analysis`,
  decision 45) / `rename` (files and directories: unsaved-changes flow over the affected open documents, reopen under
  the new paths in the same tab positions) / `delete` / `revealInFileManager` and the conflict banner intent
  `resolveConflict` (`ConflictResult.DeletedOnDisk` → close the tab without saving, `Failed` → error dialog; decision
  43) as documented on `WorkbenchState`, close/exit flows; collecting each editor's `navigationRequests` from opening
  its tab until closing it; the sub-states (`ProblemsState` and the status-bar counts only map `Workspace.problems`;
  `ProblemItem` with the snapshot's version and lineage; `OutlineUiState` with path, parse version and lineage;
  `EditorTab.conflict` from the document's conflict kind; the status-bar column with `TabStops`; `LayoutState` as live
  state, persisted by the mediator debounced and on exit, restored in `start`; the typed `DialogState`); `Workbench`
  composable and views (no logic and no coroutines for intents; they call the intents, e.g. `navigateTo(item.location)`,
  `navigateTo(outline.locationOf(node))`, `closeTab`; the size-based `SplitPane`s take `ToolWindowLayout.size`
  directly); `SettingsRepository` (JSON, corrupt-file backup named from the injected `Clock`); templates per
  `TemplateContext` (documented on `FileTemplates`, incl. the attach-by-type rule of the environment template);
  headless-safe desktop file operations and folder chooser (blocking work on `WorkbenchServices.ioDispatcher`); command
  registration (`WorkbenchCommandIds`, tool-window toggles from `toggleToolWindow(id)`; View commands drive `layout`;
  editor commands under `EditorActionIds` with `EditorActionTitles`, shortcuts from `WorkbenchServices.editorKeymap` —
  the keymap `EditorStateFactory.create` gets too —, enabled by the active editor's `enabledActions` and, except
  find/replace/find next/previous/go to line/go to definition, its `isFocused`, forwarded to it); light/dark theme
  provision from `layout`.
- **Fakes**: fake `FolderChooser`, `ProjectFileOperations` (or the real one on `@TempDir`), `WorkbenchServices` with
  fakes where useful, a test dispatcher as `ioDispatcher` and as the editors' background dispatcher (through an
  `EditorStateFactory` that calls `CodeEditorState(…, keymap = keymap, backgroundDispatcher = …)`); real everything
  else (all dependencies merged). Settings directory: `@TempDir`; clock: `Clock.fixed`.
- **Must satisfy**: spec §8 (all bullets) with unit tests per sub-state (Compose-free) and for the mediator flows (new
  file from each template, also in a directory with an implicit context — the new descriptor names the existing files,
  the new KModel/KStates use the context's types, and in a copy of single_robot_example the environment template
  attaches `cleaning_robot.kstates` to `"turtle"` —, rename and delete of open files, rename of a directory with open
  (one dirty) documents — Save/Discard reopen them under the new paths in the same tab positions, Cancel changes
  nothing —, external conflict Reload / Keep mine and Close for a file deleted on disk, two `closeTab` calls for a dirty
  tab show one dialog, navigateTo into an unopened file centres the caret, Problems and Outline navigation after edits
  above the target and with the outline taken from the last good parse, a definition lookup finishing after a tab
  switch still navigates, a burst of `resizeToolWindow` writes the settings once, editor commands other than the
  find/go-to ones are disabled while the active editor is not focused), headless UI tests for the layout, tree open,
  tab close with unsaved changes, problems navigation (also to a descriptor diagnostic → opened externally), status
  bar, the conflict banner per `EditorTab.conflict`; the desktop adapters in the headless test JVM (`supports*` false,
  calls are no-ops, nothing throws); settings persistence (tool-window map round trip, unknown tool-window names kept)
  and corrupt-file recovery.
- **Must not touch**: other modules; CONTRACT files; `…/ui/workbench/e2e`.

### WP-APP — app/ide and tools/cli

- **Owns**: package `io.karpfen.ide.app` and `io.karpfen.ide.tools.cli` (incl. `validate`) except the CONTRACT file
  `validate/ValidationReport.kt` and INTEGRATION's `e2e` test packages. Skeletons: `app/ide/…/app/IdeApplication.kt`
  (keep the signatures of `IdeApplication(…)`, `prepare`, `run` and `IdeSession`), `app/ide/…/app/IdeMenus.kt`,
  `app/ide/…/app/CrashHandling.kt`, `app/ide/…/app/Main.kt` (rewrite: `main(args)`, optional folder argument,
  `KARPFEN_IDE_EXIT_AFTER_MS` kept, `KARPFEN_IDE_HOME` copied into the system property
  `SettingsLocations.HOME_PROPERTY` before the first logger exists), `tools/cli/…/tools/cli/Main.kt` (rewrite:
  `main(args)` → exit code; keep a one-line `USAGE`), `tools/cli/…/tools/cli/CliArguments.kt`,
  `tools/cli/…/tools/cli/validate/ValidateCommand.kt` (keep the constructor signature), `…/validate/ReportFormatter.kt`,
  `…/validate/StandardOutputGuard.kt` (keep the constructor signature); new resources
  `app/ide/src/main/resources/logback.xml` and `tools/cli/src/main/resources/logback.xml` (the console-only
  `src/test/resources/logback-test.xml` of both modules is CONTRACT, rule 10).
- **Implements**: the composition root (`IdeApplication(exitAfterMs, services, workbenchStateFactory)`: `prepare` does
  all window-free wiring — services from the injected factory, the state from the injected `workbenchStateFactory`, a
  `CommandRegistry(services.platform)` (the one platform source, decision 46) with `registerCommands`, `start`, the
  initial folder (joining the `openFolder` job) — and `run` shows the window around it; `requestExit` is launched in
  the application's scope on a close request), menus from
  `CommandRegistry` + `IdeMenus.specs` through an internal Compose-free `resolveMenus(specs, registry)` that
  `IdeMenuBar` only renders (accelerators bound with `toMenuShortcut`, decision 15), native macOS menu bar, window
  title and bounds (from and to `WorkbenchState.layout`), close → `requestExit`, crash handling with a dialog
  (`WorkbenchState.dialogs.ask(DialogRequest.Error(…))` once the workbench runs), logging config (console WARN, rolling
  file `${karpfen.ide.home:-${user.home}/.karpfen-ide}/logs/ide.log`, so the IDE home override moves the log too);
  CLI argument parsing, `validate` (a workspace from the injected `WorkspaceFactory` without watching, `refresh`,
  `awaitAnalysis`, report `Workspace.problems` — no own tokenizing or merging; the whole workspace run from opening to
  closing inside the suspending `StandardOutputGuard.silenced`, which redirects `System.out` and `System.err`; report
  and CLI messages afterwards through the guard's `output`/`errorOutput`), text/JSON formatters, exit codes (1 on
  ERROR). The menus: Edit has Find Next / Find Previous; View has one toggle per `ToolWindowId.entries` (title from the
  enum, id from `WorkbenchCommandIds.toggleToolWindow`).
- **Fakes**: app tests must not run the real `WorkbenchState`/`Workbench` (WP-WORKBENCH may not be merged): test
  `IdeApplication.prepare` headless with a fake `WorkbenchState` (an interface) from `workbenchStateFactory` and a
  recording services factory, `IdeMenus.specs`, `resolveMenus` (with a fake `CommandRegistry` if WP-FOUNDATION is not
  merged yet), `CrashHandling` with a recording callback, the home-directory resolution of `main`. CLI tests use the
  real workspace (WORKSPACE/LANG merged) over `Examples.copyTo(@TempDir)` and
  `DescriptorExamples.copyExplicitContext(@TempDir)`, through `WorkspaceFactory(WorkspaceOptions(watch = false, …test
  dispatchers…))`.
- **Must satisfy**: spec §9 and §11 item 5 (`karpfen validate <examples dir>` exits 0 with no ERROR output; a broken copy
  exits 1 with `path:line:col: error: … [code]`, one-based; descriptor findings are reported with their positions;
  loose files' syntax errors (e.g. a second, broken metamodel in one directory) are reported; JSON format parses). A
  broken copy writes nothing to `System.err` except the CLI's own messages and nothing to `System.out` except the
  report (capture both streams). A test that the app and CLI test classpaths resolve `logback-test.xml` (not
  `logback.xml`), e.g. from the logback context's configuration URL.
- **Must not touch**: other modules; `logback.xml` must not be added anywhere else (rule 10).

### INTEGRATION — end-to-end tests and documentation

- **Owns**: new test packages `…/e2e/` in `app/ide`, `tools/cli`, `ui/ui-workbench` and `ui/ui-editor`
  (`io.karpfen.ide.ui.editor.e2e`: `CodeEditor` together with `EditorPopups`, which neither VIEW nor POPUPS may test);
  `docs/p1-manual-test.md` (new); `README.md` (status, module table, run instructions). Defect fixes in owned files of
  merged WPs (§5 rule 7).
- **Implements**: end-to-end tests over the merged system, built with the production wiring and test values
  (`defaultWorkbenchServices(settingsDirectory = @TempDir, workspaceOptions = …TestTimeSource…, fileWatcher = fake,
  clock = Clock.fixed, fileOperations = recording fake, folderChooser = fake, …)` and `IdeApplication(…).prepare`): open the examples folder in a `WorkbenchState`, edit, see
  diagnostics and completion, save/undo (coalescing on the test time source), external change through the fake
  watcher; in `ui.editor.e2e` completion opening on typing with keys routed through `handleKey`, find-bar Escape
  returning focus, the context menu by right-click; DoD checks of spec §11 not already covered by a WP.
- **Must satisfy**: spec §11 items 1–7 (composite mode in CI; `KARPFEN_IDE_HOME=<temp dir> KARPFEN_IDE_EXIT_AFTER_MS=6000
  ./gradlew :app:ide:run --args=<examples dir>` exits 0 with no ERROR in `<temp dir>/logs/ide.log`; the 10-minute
  manual smoke test document for the owner). Also: the Problems view, the status-bar counts and `karpfen validate`
  agree on the examples plus `DescriptorExamples.copyExplicitContext`, before and after opening and closing files; one
  Cmd/Ctrl+Z in the editor undoes exactly one step (headless: the stroke is consumed by the editor and no command
  runs; the manual test covers the native macOS menu bar); Cmd/Ctrl+X with focus in the Project tree leaves the
  editor text unchanged (the Cut command is disabled while the editor is unfocused); find next (key and find bar) to
  an off-screen match scrolls it into view in `ui.editor.e2e`. The manual test lists "Cmd+/ (Cmd+Shift+7) toggles a
  comment on a German macOS layout, `#` inside an EVAL body".
- **Must not touch**: CONTRACT files and build files (except test-only additions, rule 7). Contract gaps found are
  reported through §5 steps 3–4; plain defects in owned files are fixed under §5 rule 7.

## 5. Contract change protocol

1. A WP may **add** to its own module's API (new types, new functions, new interface members **with default
   implementations**) when the addition is needed for its own work and breaks no other WP's fakes or callers.
2. A WP **never changes another WP's files** and never changes CONTRACT files, not even for "obvious" fixes (the one
   exception is rule 7).
3. A **contract gap** (a missing member, a wrong type, an invariant that cannot hold, a spec conflict) is **reported,
   not patched**: describe it in the WP's final report (file, symbol, problem, proposed change, who is affected) and
   work around it locally (e.g. an internal helper, a fake, a documented limitation) so the WP can still merge green.
4. The architect applies accepted contract changes (reported by any WP, INTEGRATION included) in one commit that
   updates all affected callers and fakes and this document.
5. Changing a public signature in an owned file, removing a declaration, adding an abstract member to a consumed
   interface, or renaming a package always goes through steps 3–4.
6. Shared mechanics are never copied into a second WP (spec §1a). If two WPs need the same mechanism and it is not in a
   CONTRACT file, that is a contract gap (step 3), not a reason to write it twice.
7. **Defects found in integration.** Some seams first run together in INTEGRATION (`IdeApplication.run` with the real
   window, `CodeEditor` with `EditorPopups`, the launch smoke). INTEGRATION fixes a plain defect (behaviour that
   violates the documented contract or the spec) in any owned file of a merged WP, without changing public signatures
   or CONTRACT files: each fix comes with a regression test (in INTEGRATION's `e2e` package or the owning WP's test
   package) and one line in INTEGRATION's report (file, defect, test). Anything that needs a contract change goes
   through steps 3–4.

## 6. Decisions of the contracts step that refine the spec

Where the spec sketch and the design principles (§1a) conflicted, the principles won:

1. Mutable components are interfaces with internal implementations and Kotlin factory functions named like the
   interface (`EditableText(…)`, `DocumentSession(…)`, `Workspace(…)`, `CaretModel(…)`, …) instead of classes.
2. `EditableText.apply` and `replaceAll` return `TextChange?` (null for no-ops, which produce no version).
3. Time is `kotlin.time.TimeSource` (tests: `TestTimeSource`); debounce uses injected dispatchers (virtual time).
4. `DocumentParser.parse(snapshot, mode: SemanticMode)`. In a workspace **every** session uses `SYNTAX_ONLY` (v1.2;
   v1.1 did so only for `EVENTS.kmeta`, whose payloads reference domain types and only convert with base types): the
   project analysis is the only place that converts files, so each file is converted once and opening a file never
   changes what is reported (decision 28). Since v1.3 `SYNTAX_ONLY` is also the default of `DocumentSessionConfig`
   and `DocumentParser.parse` (decision 46); `STANDALONE` is opt-in for documents converted on their own.
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
    POPUPS own disjoint files. `onNavigate` serves simple embeddings; the workbench collects the editors'
    navigation requests itself (decision 30).
12. Additions: `UndoManager.breakCoalescing()`, `Workspace.contexts/isAnalyzing/awaitAnalysis()`, `DiagnosticCodes`,
    `CompletionKind` values, `ThemeMode.SYSTEM`, `OpenResult`/`SaveResult` result types (I/O errors are values).
13. Implicit contexts: a directory that contains a descriptor is covered by it; its karpfen files not named by a
    descriptor are *loose files* (`ContextResolution.looseFiles`): tokenized and parsed (their syntax diagnostics
    appear in Problems and in the CLI), never converted — open or not (decision 29 completes the definition).

Added by the contract review (v1.1):

14. **Files the editor cannot open** (`*.kenv.json` and other non-karpfen files): `OpenResult.Unsupported` makes the
    workbench open them in the system's default application (`ProjectFileOperations.openExternally`), also when
    navigating from the Problems view. Resolves open question 1 for phase 1 (see §8).
15. **Key routing** (resolves open question 4): the focused component handles and consumes its key strokes first
    (`CodeEditor` through `CodeEditorState.handleKey` — completion first, then the keymap — consuming exactly the
    strokes it handles; text fields such as the find bar natively); a menu accelerator only sees strokes nobody consumed
    (Swing key bindings skip consumed events; on macOS the screen menu bar delivers key equivalents as Java key events
    first). There is one editor keymap per workbench (`WorkbenchServices.editorKeymap`): every editor is created with
    it and the editor commands in the menu carry its `shortcutFor(id)` (one source of shortcuts, v1.2), take their
    enablement from the active editor's `enabledActions` (and, v1.3, its focus, decision 36) and are forwarded to the
    active editor. INTEGRATION proves one Cmd/Ctrl+Z = one undo step; the manual test covers the native macOS menu bar.
16. **Shared ANTLR mechanics** are complete and tested in the contracts step, like `CodePointOffsets`:
    `IdeErrorListener` (LEXER and PARSER diagnostics instead of console output), `KarpfenLexers` (the only map from a
    language to its generated lexer, listeners replaced), `LexerGaps` (comments and unrecognized input the KMeta/KModel
    lexers drop) and `EvalBodies` (EVAL bodies balanced on brace tokens like the dsl-tools parser, and their embedded
    pieces). WP-TOKENS and WP-PARSE both build on them and never copy them; EVAL references and folding come from the
    lexical scanner in both WPs, so highlighting, navigation and folding agree on every input.
17. **One problems rule**: `Workspace.problems` (`WorkspaceProblems`) is the only place that combines the diagnostics of
    the files (open document → its session's diagnostics; other karpfen file, in a context or loose →
    `AnalyzedFile.ownDiagnostics` plus project diagnostics; descriptor → project diagnostics). The Problems view, the
    status bar and `karpfen validate` only map and filter it. The analyzer records tokenizer and loading diagnostics on
    every `AnalyzedFile`, loose files included. `SourceLoader` is the one loading rule (UTF-8 with replacement, byte
    order mark removed and restored on save, separators normalized, findings as external source
    `ExternalDiagnosticSources.FILE`); descriptor ranges refer to the normalized text. Decision 28 makes the per-file
    merge itself one function shared by sessions and analyzed files.
18. **Editor contracts for parallel work**: the popups see `EditorPopupModel` (interface segregation), not the whole
    `CodeEditorState`; view requests are pending *state* with consume functions (`scrollRequest`, `focusRequested`),
    so requests made before `CodeEditor` is composed are not lost, and `navigationRequests` is the only event stream
    (`EditorOverlayHost.navigate`/`requestEditorFocus` were duplicates and are gone); `enabledActions`/`isEnabled`
    expose action enablement; hover content is computed off the UI thread (v1.3: internal to the state, decision 36);
    `bracketMatch` is versioned (`BracketMatchSnapshot`); the background
    dispatcher is injected. Find/replace and completion accept edit through `EditorAction`s run by an
    `EditorActionExecutor` (Command pattern for every edit); the completion model observes `caret.state` itself (no
    `onCaretMoved`) and calls a versioned `CompletionSource.items(snapshot, offset)`; auto-inserted closers are tracked
    per editor by `AutoClosedPairs` in the `EditorActionContext`; `CaretModel(…, tabWidth)` computes preferred columns
    with tabs expanded and breaks undo coalescing on explicit moves. v1.2 adds decision 30.
19. **Language services**: `FeatureRequest` (was `DocumentContext`; "context" now only means caret, modeling and action
    contexts) with `contextAnalysis`, also for `BracketMatcher`; the shared `TypeCatalog` (IDE-owned
    `TypeInfo`/`MemberInfo` with hover texts and declaration `Location`s, memoized per `ContextAnalysis`) serves all
    three languages and `PathScope` is built on it (`PathMember` wraps `MemberInfo`; the KStates scope at an offset is one
    collaborator, `KstatesPathScopes`), so no dsl-tools type appears in the providers' or the resolver's API;
    `LanguageDocument` has `lastGoodParse`, `isConsistent`, `currentParse` and a staleness rule, and
    `LanguageDocument.of(session, snapshot)` tokenizes synchronously when the session's tokens lag;
    `Location.version`/`lineage` let navigation map ranges into the current text (decision 26);
    `defaultLanguageServices(…, onProviderFailure)` makes the facade's swallowed exceptions observable.
20. **Open/Closed for languages**: file names map to languages by one closed rule, `KarpfenLanguage.fromFileName` (the
    enum's extension), used by `LanguageRegistry.languageOf` and by `FileKind.of` alike (v1.2: the
    `LanguageSupport.matches` hook is gone, because the closed `FileKind` bypassed it and the two could disagree). The
    product family of a language is split by layer — `LanguageSupport` (tokenizer, parser) in core-document,
    `LanguageFeatures` (editor features) in core-lang — and `LanguageContractTest` checks that every registered language
    has features. Accepted closed per-language switches and vocabularies, i.e. everything a fourth language adds
    entries to: `KarpfenLanguage` (enum, incl. the file-name rule), `FileKind` and `FileKind.of`, `KarpfenLexers.create`
    (the only language → generated-lexer map), `DslConverter` (one method per dsl-tools converter; the foreign API is
    closed), `DslModel` (sealed), the role fields of `ModelingContext` and `ContextAnalysis` (metamodel, events, models,
    state machines), the shared vocabularies with language-specific members (`TokenKind`, `SymbolKind`,
    `ReferenceKind`, `SemanticKind`, `HighlightStyle`) and the `TokenKind`/`SemanticKind` → `HighlightStyle` mapping of
    `DefaultHighlightProvider`, `FileTemplateKind`, `KarpfenIcons`. Plus one `LanguageSupport` and one
    `LanguageFeatures`; no existing language class changes.
21. **Injected time, threads and collaborators**: `WorkspaceOptions.timeSource` for the documents' `EditableText`s;
    every text mutation on the dispatcher of the workspace's scope (single writer); `setExternalDiagnostics` is
    thread-safe; the `Workspace(…)` factory takes `sourceLoader`, `descriptorReader`, `contextResolver` and `analyzer`
    and passes every collaborator — the document side `WorkspaceDocuments` and the scanner included — to the workspace
    through `WorkspaceCollaborators` (v1.2); `CodeEditorState(…, backgroundDispatcher)`; `WorkbenchServices` is a
    plain class with `ioDispatcher`, and `defaultWorkbenchServices(…)` injects the dispatchers, `workspaceOptions`,
    `fileWatcher` and `clock` everywhere (decision 34); `SettingsRepository(…, clock)`;
    `ValidateCommand(…, workspaceFactory)`; `StandardOutputGuard.silenced` suspends.
22. **Symbol data for core-lang**: `SymbolDeclaration.typeName` (object types, property and relation targets, parameter
    and return types), the `container` rules, `SymbolReference.qualifier` (KModel keys → the enclosing object's type;
    KStates `SETOBJ`/`APPENDOBJ` relation → the source path; EVENT_NAME → the domain) and `OutlineNode.detail` per kind
    are documented on the types; WP-LANG relies on nothing else of WP-PARSE's output.
23. **Factory naming**: a factory named like its interface creates the default implementation and lives in the
    interface's package (`EditableText(…)`, `Workspace(…)`, `WorkspaceFactory(…)`, `SourceLoader(…)`, `TypeCatalog()`,
    `CaretModel(…)`). A descriptive name is used when the factory assembles a product family
    (`defaultLanguageRegistry()`, `karpfenLanguageFeatures()`, `defaultEditorActions()`), lives in another package than
    its interface to keep the packages acyclic (`defaultLanguageServices(…)`) or in an adapter package
    (`desktopProjectFileOperations()`, `nativeFolderChooser()`), or provides a platform default
    (`defaultEditorKeymap(…)`, `defaultWorkbenchServices(…)`).
24. **Concrete state holders** (exception to decision 1): `EditorOverlayState` (complete, without collaborators — it is
    its own fake) and the workbench sub-states (`ProjectTreeState`, `EditorTabsState`, `OutlineState`, `ProblemsState`,
    `StatusBarState`, `DialogState`, `LayoutState`) stay public classes: plain state holders without I/O whose only
    collaborator is a coroutine scope, so tests use the real ones. Their declared public members are frozen (§3). I/O
    goes through ports (`ProjectFileOperations`, `FolderChooser` in `files`; persistence of the layout through the
    mediator and the `SettingsRepository`), whose desktop adapters live in `files.desktop`. Likewise
    `DefaultCodeEditorState` creates its per-editor models (`CaretModel`, `AutoClosedPairs`, `FindModel`,
    `CompletionSessionModel`) instead of receiving them (v1.2, recorded exception to decision 21): they are bound to
    this editor's text, caret and executor, are Compose-free and deterministic (time and threads come from the
    injected scope and background dispatcher), and the editor's tests use the real ones (WP-EDITOR-MODEL is merged
    before WP-EDITOR-VIEW); a models factory would only add a parameter nobody fakes.
25. **Fat facades are coordinators**: `DefaultCodeEditorState`, `DefaultWorkbenchState` and `DefaultWorkspace` keep
    their interfaces but delegate to internal single-purpose collaborators listed in their KDoc (spec §1a, ≤ ~300
    lines per class).

Added by the second contract review (v1.2):

26. **Text lineages and versioned ranges**: versions count per `EditableText` (a reopened document starts again at 0),
    so every `TextSnapshot` carries a `TextLineage` (identity, shared by all snapshots of one text; a snapshot created
    on its own — a disk load, a test value — is a lineage of its own). `Location(path, range, version, lineage)`:
    `version` is the version of the outcome the range came from (the request's text or current parse, the last good
    parse for declarations only known from it, the analyzed snapshot for other files, the diagnostics' snapshot for
    problems). Navigation maps a range with `EditableText.mapRange` (v1.3, decision 35), which answers null outside the
    text's own lineage, and clamps then; `DocumentSession.setExternalDiagnostics(source, snapshot, …)` ignores
    snapshots of another lineage.
    `ProblemItem` and `OutlineUiState` carry version and lineage (`ProblemItem.location`, `OutlineUiState.locationOf`).
27. **Embedded regions** (blocking finding): `TokenizeResult.embeddedRegions` publishes the EVAL body content ranges of
    the one scanner (`EvalBodies`, filled by the KStates tokenizer), with `embeddedRegionAt(offset)` (inclusive bounds).
    It is the only answer to "is this embedded Python?" outside core-document: toggle comment decides `#` vs `//` per
    line by the line's first non-blank offset, the caret context's `EmbeddedContext.bodyRange` is the same lookup; no WP
    derives EVAL bodies from token kinds.
28. **One merge rule, one converter**: `mergeDiagnostics(lexical, parse, external)` (core-document `session`, complete)
    is the per-file merge used by `DocumentSession.diagnostics` and `AnalyzedFile.ownDiagnostics`; in a workspace every
    session is `SYNTAX_ONLY` (decision 4), so DSL_TOOLS diagnostics come only from the project analysis, for open and
    unopened files alike, and the IDE and the CLI report the same. FILE findings follow the disk: a successful save
    clears them, every reload replaces them. `VersionedDiagnostics` carries the snapshot its ranges refer to, so
    `FileProblems.positionOf` is exact while the diagnostics lag the live text.
29. **Loose files** (completes decision 13): `ContextResolution.looseFiles` is every karpfen source of the tree that is
    in no context's files — also the metamodels an implicit context did not choose (`context-multiple-metamodels`), so
    their syntax errors appear in Problems and in `karpfen validate`. Loose files are never converted, open or not.
30. **Editor input and background work live in the state**: `CodeEditorState.handleKey` (completion first, then
    keymap; true exactly when handled), `pointerHover` (the 600 ms `HOVER_DELAY` on the editor's scope, virtual time
    in tests) and `openContextMenuAt` (caret moves to the click unless inside the selection) make the input policy
    Compose-free; the state reveals the primary caret after every executed action and typed text
    (`EditorUiPort.revealCaret` is gone; actions never scroll). `navigationRequests` are collected by their owner for
    the editor's whole life (the workbench mediator; `CodeEditor.onNavigate` only for embeddings), so no lookup is lost
    on a tab switch. `CompletionSource.items` suspends (the editor state's source switches to the background
    dispatcher; the model never names one) and `FindModel(…, backgroundDispatcher)` computes matches off the UI thread.
    `EditorOverlayState.findFocusRequests` lets a repeated Cmd/Ctrl+F refocus the find field; `ContextMenuRequest`
    coordinates are in the `EditorOverlayHost` space; find matches are drawn only while the find bar is open.
31. **ui-workbench by feature** (spec §1a): each feature package (`projecttree`, `tabs`, `outline`, `problems`,
    `statusbar`, `dialog`, `layout`) holds its Compose-free sub-state and UI values, its `view` sub-package the
    composables; `state` holds only the mediator, its services and internal collaborators. Every ui-workbench package
    but the root, the `view` packages and `files.desktop` is Compose-free (architecture test). The sub-states' mediator
    operations (`EditorTabsState.open/remove`, `ProjectTreeState.setTree`, every `bind*`, `LayoutState.restore`) are
    `internal`, so views cannot bypass coordinated flows (e.g. close a tab without the unsaved-changes dialog). Tool-window
    visibility and sizes, theme and window bounds are the live `LayoutState`, persisted by the mediator (debounced and
    on exit) as `IdeSettings.toolWindows: Map<String, ToolWindowLayout>` by `ToolWindowId.name` (new tool windows need
    no schema change). Dialogs are typed: `DialogRequest<R>` and `DialogState.ask(request): R` return only the answers
    a dialog has. Templates receive a `TemplateContext` (directory, its current context, domain types, objects with
    their types and the state machines' attached types; v1.3, decision 45).
32. **Naming convention** (UI modules): mutable Compose-free state holders are `*Model` in `ui.editor.model`
    (`CaretModel`, `FindModel`, `CompletionSessionModel`) and `*State` everywhere else (`CodeEditorState`,
    `EditorOverlayState` — was `EditorOverlayController` —, the workbench sub-states, `WorkbenchState`); immutable
    values a view renders are `*UiState` (`CompletionUiState`, `OutlineUiState`, `ProblemsUiState`, `StatusBarUiState`
    — was `StatusBarInfo` —, `LayoutUiState`); immutable versioned results are `*Snapshot` or `Versioned*`
    (`TextSnapshot`, `CaretSnapshot`, `HighlightSnapshot`, `BracketMatchSnapshot`, `ViewportSnapshot`,
    `VersionedDiagnostics`; `FindResult` for the find state); role interfaces keep role names (`EditorPopupModel`,
    `EditorViewport`, `EditorOverlayHost`). "Reveal" means "make visible in this component"
    (`ProjectTreeState.reveal`, `EditorViewport.reveal`); showing a file in Finder/Explorer is `revealInFileManager`.
    Sub-states bind with `bindEditor`/`bindWorkspace`. `KarpfenLanguage.extension` is without dot (`kmeta`),
    `FileTemplateKind.suffix` with dot (`.kenv.json`). The language services are `languageServices` wherever a
    `LanguageServices` is meant (`CodeEditorState`, `EditorStateFactory`); `services` is the `WorkbenchServices`.
33. **Commands are objects, titles and shortcuts have one source**: `Command`, `ContextMenuAction` and `StatusBarItem`
    hold behaviour and are plain classes (identity equality), not data classes. `EditorActionTitles` (next to
    `EditorActionIds`) is the one source of the editor actions' labels for the context menu and the window menu; the
    one keymap is `WorkbenchServices.editorKeymap` (decision 15).
34. **Test seams of the composition roots**: `defaultWorkbenchServices(…, workspaceOptions, fileWatcher, clock)` (end-to-end
    tests run the production wiring with a `TestTimeSource`, a fake watcher and a fixed clock); `WorkspaceFactory` lives
    in core-workspace and is shared by the workbench and `ValidateCommand(outputGuard, workspaceFactory)`;
    `IdeApplication(…, services, workbenchStateFactory)` with `prepare(scope, initialFolder): IdeSession` (all
    window-free wiring, headless-testable) and `run`; the IDE home is injectable (`KARPFEN_IDE_HOME` →
    `SettingsLocations.HOME_PROPERTY`, read by `SettingsLocations.defaultDirectory()` and `logback.xml`), so launch
    smoke tests never touch the owner's real settings and log.

Added by the third contract review (v1.3):

35. **One range-mapping rule** (blocking finding): `EditableText.mapRange(range, fromVersion, fromLineage, toVersion)`
    (and an overload taking the source snapshot) is complete in the CONTRACT file on top of `mapOffset`: never throws;
    null for another lineage, a version newer than the target or the current one, and evicted history; start
    `Bias.RIGHT`, end `Bias.LEFT` (decorations never grow over text inserted at their bounds); an inverted result
    collapses to the empty range at the mapped end; an empty range stays empty (mapped with `RIGHT`). Mapping
    (`mapOffset`, `mapRange`) is thread-safe — the edit log is published with the snapshots — because the session's
    diagnostics merger and the editor's highlight pipeline run on background dispatchers (the reviewer proposed "writer
    thread only"; that would have forced both onto the UI thread). The session merger, the editor's decorations and
    `WorkbenchState.navigateTo` use it; nobody maps the two bounds of a range separately. `mapOffset` remains the point
    mapping of carets and tracked pairs.
36. **Editor roles and focus**: `CodeEditorState` extends three role interfaces — `EditorViewModel` (what `CodeEditor`
    needs: geometry, render results, pending requests, input intents), `EditorPopupModel` (the popups) and
    `EditorHandle` (the workbench: session, caret, settings, enablement, focus, navigation requests, `execute(id)`,
    `scrollTo`, `requestFocus`, `close`). `OutlineState`/`StatusBarState` bind and the mediator's collaborators work on
    `EditorHandle`; `EditorStateFactory` and `EditorTabsState` keep the full `CodeEditorState` because the editor area
    renders it. `languageServices`, `actions`, `keymap`, `hoverAt` and `languageDocument` left the public interface
    (internal collaborators). New intents: `onFocusChanged` (with `EditorHandle.isFocused`; focus loss closes completion
    and the hover) and `pointerPressed`/`pointerDragged`/`pointerReleased` (the click policy — caret, word, line,
    Shift-extension, primary-modifier go to definition — is Compose-free; the view only hit-tests and runs the drag
    auto-scroll timer). The menu forwards text-editing editor commands only while the active editor is focused; find,
    replace, find next/previous, go to line and go to definition run regardless.
37. **Find navigation is revealed and an action; owners may add sub-packages**: `EditorActionIds.FIND_NEXT` /
    `FIND_PREVIOUS` (Cmd+G / Shift+Cmd+G on macOS, F3 / Shift+F3 elsewhere; `EditorUiPort.findNext`/`findPrevious`).
    `FindModel` selects matches only through its executor (an internal select-match action), so the editor's reveal
    after `execute` covers the find bar, the keys and the menu alike; `setQuery` selects the first match at or after the
    selection start, `next`/`previous` first select an unselected current match. Separately, §1 now lets an owner create
    sub-packages of its packages; the architecture rules for the editor (`state..`, `view..`, `popup..`) match
    sub-packages, and package cycles stay forbidden.
38. **Tokens of exactly one version have one owner**: `DocumentSession.tokensFor(snapshot)` (complete in the contract)
    returns the session's tokens when their version matches and tokenizes synchronously otherwise.
    `LanguageDocument.of`, the editor's action context and the workspace's analysis sources use it.
    `EditorActionContext.tokens` belongs to the current text (MUST), so toggle comment never compares current offsets
    with an older version's EVAL regions; `EditorAction.isEnabled` reads neither tokens nor the clipboard (Paste is
    always enabled and a no-op on an empty clipboard), so enablement never goes stale and never tokenizes.
39. **Semantic highlighting does not flicker**: `HighlightStyle.isSemantic` marks the styles of the semantic layer and
    `overlayHighlights(base, overlay)` (complete, core-lang) is the one overlay rule, used by the highlight provider and
    by the editor, which lays the previous snapshot's semantic spans — mapped with `mapRange` — over fresh lexical spans
    while the parse lags the tokens.
40. **Display columns have one home**: `TabStops(tabWidth)` in `ui.editor.model.caret` (complete) converts between line
    columns and display columns; the caret model (preferred column), the editor view (rendering, hit-testing, caret x)
    and the status bar ("Col y" is the display column) use it. It lives in ui-editor because display columns are a UI
    concern (`TextPosition`).
41. **Layout-aware punctuation keys**: `KeyCode`'s punctuation codes denote characters; `toKeyStroke` reports the code of
    the produced character without the Shift the layout needed (German Cmd+Shift+7 → Cmd+/), so primary+/ works on the
    owner's layout without character fields on `KeyStroke` (the reviewer's proposal) or layout-specific second bindings.
42. **Workbench intents own their lifetime**: the UI intents of `WorkbenchState` are non-suspending, launch their flow in
    the mediator's scope one at a time and return its `Job`; views and commands call them directly and never launch
    coroutines for intents, so a rename survives its tree row and a double click cannot open two dialogs. `start` and
    `requestExit` (composition root) still suspend. Renaming a directory with open documents runs the unsaved-changes
    flow over them and reopens them under the new paths in the same tab positions.
43. **Conflict protocol without exceptions**: `OpenDocument.resolveConflict` returns a `ConflictResult` (`Reloaded`,
    `Kept`, `DeletedOnDisk`, `Failed`, `NoConflict`); `ConflictResolution.RELOAD` means "take the disk state", which for
    a deleted file the mediator turns into closing the tab. `EditorTab.conflict: ExternalChangeKind?` lets the editor
    area pick the banner (Reload / Keep mine or Close / Keep mine); `EditorTabsState.documentFor` is internal.
44. **dsl-tools output and broken files**: the analyzer converts only syntax-clean files and skips conversions that need
    an unconverted metamodel, reporting nothing of its own for them (no extra INFO: the broken file's syntax errors
    explain it); `DslConverter` documents both of dsl-tools' output streams and the syntax-clean precondition;
    `StandardOutputGuard.silenced` redirects `System.out` and `System.err`, and `ValidateCommand` wraps the whole
    workspace run in it. The console-only `logback-test.xml` of `app/ide` and `tools/cli` is CONTRACT.
45. **Templates from IDE-owned syntax**: `TemplateContext` carries the metamodel's TYPE names, the first model's objects
    with their types (`TemplateObject`) and each state machine's `ATTACHED TO` type, all from the syntax indexes of the
    latest analysis, so the mediator never walks dsl-tools models and a template is useful while the metamodel does not
    convert; the environment template attaches each state machine to the first object of its type.
46. **One source per fact**: the platform comes from `WorkbenchServices.platform` (= its `editorKeymap.platform`;
    `IdeApplication` lost `settingsDirectory`/`platform`, the `CodeEditorState` factory lost `platform`); the default
    `SemanticMode` is `SYNTAX_ONLY` everywhere (decision 4); workspace diagnostic codes live in core-workspace's
    `WorkspaceDiagnosticCodes` (core-document keeps lexer, parser, EVAL, dsl-tools and internal codes); tool-window
    toggle ids derive from the enum (`WorkbenchCommandIds.toggleToolWindow(id)`, `ToolWindowId.title`) instead of one
    constant per window; `SplitPane` is size-based (`fixedSide`, `fixedSize`, `onFixedSizeChange`), matching the
    persisted dp sizes of `LayoutState` so the workbench converts nothing (spec §6 sketched a fraction); port members
    are named `revealInFileManager`/`supportsRevealInFileManager` (decision 32); `WorkspaceCollaborators` is a plain
    class and the scanner an internal interface; the desktop adapters are headless-safe and injectable through
    `defaultWorkbenchServices(…, fileOperations, folderChooser)`; `TreeView` scrolls a changed selection into view.
47. **Shell review fixes (workbench, app, CLI)**: `WorkbenchServices` lost `languages` (a workspace's languages are
    `Workspace.languages` from the factory; decision 46). Saving never silently overwrites an external change: Save,
    Save All and Save in the unsaved-changes dialog ask `DialogRequest.ConfirmOverwrite(paths)` (Confirm / Cancel) for
    documents with an unresolved conflict; Cancel keeps the disk version and the conflict, activates that tab (its
    banner shows) and stops a close or exit; the tab strip marks tabs in conflict (warning icon, banner message in the
    tooltip). The dialogs are modal for commands: `registerCommands` registers through a decorator that disables every
    command while `DialogState.current` is set. Close requests coalesce per tab (a repeated `closeTab` returns the
    queued or asking job; `closeActiveTab` closes the tab active at the call), refining decision 42 and §7 item 21.
    `IdeApplication.prepare` joins the initial folder's job unless its flow asks a dialog first (only the window can
    show it, so the startup must not wait for the answer). `openFolder` checks the folder before the unsaved-changes
    flow: a missing one keeps the open workspace and leaves the recent folders (`IdeSettings.withoutRecentFolder`).
    `ProjectFileOperations.rename` renames case-only changes through a temporary sibling (case-insensitive file
    systems). A `settings.json` that is not UTF-8 is corrupt (backed up and reset); one that exists but cannot be read
    is never written over. `karpfen validate` reports a karpfen file or a directory of the scanned tree it cannot read
    as an ERROR `unreadable` at 1:1 (exit 1), next to `Workspace.problems`, which stay the IDE's.

Added by the phase-1 review fixes (core):

48. **Keep mine leaves the document dirty**: `UndoManager.invalidateSavePoint()` makes no history state the saved
    state until the next `markSavePoint`. `ConflictResolution.KEEP_MINE` calls it, because the disk then holds the
    external text or nothing, which no undo state matches: the kept buffer is saved by Save All, guarded by the
    unsaved-changes prompt, and a later change on disk is a conflict instead of a silent reload.
49. **A save marks the state it wrote**: `UndoManager.checkpoint(): HistoryState` (an opaque state identity; it ends
    the coalescing run) and `markSavePoint(state)`. The saver takes the checkpoint with the snapshot it writes and
    marks it after the write, so typing during the write keeps the document dirty, and undoing that typing (not
    undoing past the save) makes it clean; before, the save point stayed at the previous save.
50. **External diagnostic sets never expire**: the session's merger re-anchors each external set at the snapshot of
    every merge (`ExternalDiagnosticsAnchors`) and maps it from there, so FILE findings, reported only on open, reload
    and save, survive more edits than `EditableText` retains (they used to vanish after 1000 edits), and a merge maps
    only the versions since the previous one. Mapping step by step differs from mapping from the reported version only
    for a range that collapsed to an empty one: it then stays behind text typed at it, like any empty range.
51. **Files named by a descriptor are covered wherever they lie** (completes decisions 13 and 29): an implicit context
    is formed only of a directory's karpfen files that no explicit context contains, and a directory left without such
    files forms none. A descriptor naming `models/robot.kmodel` thus no longer makes `models/` a second context that
    converts the model again and reports a false `context-no-metamodel`. The descriptor's own directory stays covered
    as a whole (its unnamed files are loose, open question 2).
52. **A repeated descriptor key is invalid**: a known key given twice in one object is an ERROR `descriptor-invalid`
    at the repetition (no descriptor, no context). kotlinx.serialization decodes the last value while other JSON
    readers take the first or fail, so accepting it would let the IDE analyze another file than the runtime runs; the
    position tree's `member(key)` follows kotlinx (last wins) wherever it is still asked. No new diagnostic code (the
    contract test pins the set of workspace codes).
53. **One home for the lexical facts** (completes decision 46): `KarpfenSyntax` in core-document's `syntax` package
    declares the identifier characters `[A-Za-z0-9_]`, the primitive types and the KStates operations (and which of
    them take a relation). The KStates tokenizer finds the operations' token types by name in the lexer vocabulary,
    the action walker, `EvalBodies`' path scanner, core-lang's `TokenView`/`KstatesSlots`/completion and ui-editor's
    `WordBoundaries` read them there; `MemberInfo.PRIMITIVE_TYPES` and the other copies are gone, and
    `LexicalFactsTest` (architecture) rejects a second declaration. Undo coalescing (core-text, below core-document)
    and the quoted-name error locator keep their Unicode word rules: they describe text and quoted names, not
    identifiers.

Added by the review fixes of the editor group (r1):

54. **Find never matches on the UI thread** (spec §1): `FindModel.query` is the query in use, set synchronously by
    `setQuery`; `state` carries the matches of `FindResult.query` once the background search published them. `next`,
    `previous`, `replaceCurrent` and `replaceAll` (now `Unit`) wait for the matches of the current text and query, run in
    call order, and are dropped by a new query; regex replacements are expanded on the background dispatcher. Search and
    expansion have a time budget (`FindSearch`, 2 s): a catastrophic regular expression ends in the error "Search took
    too long". This replaces the synchronous recomputation of WP-EDITOR-MODEL. The find bar renders `find.query` (text
    and toggles); `FindBarFields` keeps only the fields' selection, composition and the replacement, and the editor's
    find next and selection seeding read `find.query`.
55. **Clipboard results and whole lines** (CONTRACT `EditorAction.kt`): `ClipboardPort.setText(text, wholeLine = false)`
    returns false when the clipboard could not be written, and cut then keeps the text; `holdsWholeLine()` tells paste
    that the clipboard still holds a line copied or cut without a selection, which is inserted above the caret's line
    (copy + paste duplicates a line, cut + paste moves it). The system port marks such a line with a JVM-local flavor,
    so the mark follows the clipboard's content between editors and vanishes when anything else is copied.
56. **Popups agree with the keyboard and the pointer**: `EditorPopupModel.pointerOverTooltip(over)` (CONTRACT) keeps the
    hover tooltip while the pointer is on it; leaving its range and the tooltip hides it after `HoverController.HIDE_GRACE`
    (300 ms), typing at once. A completion list that cannot be shown (caret scrolled out of view) is closed
    (`PopupIntents.completionHidden`), so a hidden session takes no keys; an outdated completion result is requested
    again only while the caret is still in the word it completes (`CompletionTriggers.stillHolds`), never after a line
    break or a space. Tab and Shift+Tab never move focus out of the editor, also when they change nothing; the caret
    reveal's margin shrinks to what fits into a small viewport.

Added by the review fixes of the core group (r2):

57. **dsl-tools errors land on the offending key** (CONTRACT `DslErrorLocator.kt`, documentation only): dsl-tools
    quotes the offending key, id or type first and its context after it, so the locator takes the symbols of the name
    quoted first as candidates and ranks them by the other quoted names: inside a declaration they name (the closest
    first), qualified with a quoted name, a declaration the languages' own facts reject (a `prop` of a non-primitive
    type), then document order. Before, the first symbol in document order of any quoted name won, usually an earlier,
    valid object of the same type.

## 7. Review findings not adopted

1. **A core-lang-owned `ProjectKnowledge` port instead of the workspace's `ProjectAnalysis`.** The providers need the
   complete per-context analysis (converted metamodels and models, every file's snapshot and parse outcome); a port
   would mirror `ContextAnalysis` field by field and need an adapter in every composition root. core-lang depends only
   on immutable values of core-workspace (no `Workspace` behaviour), `core/* → core/*` is allowed by the module matrix,
   and `TypeCatalog` (decision 19) already keeps the dsl-tools `meta.*` model out of the providers' APIs. That
   ui-editor sees core-workspace's values transitively through `api` is accepted for a UI module.
2. **Workbench sub-states and `EditorOverlayState` as interfaces with internal implementations.** See decision 24.
3. **A `bindAccelerator` flag on `MenuEntry.Item` (display-only menu shortcuts).** Not needed with decision 15, and
   Compose's `MenuBar` cannot show a shortcut without binding it. If INTEGRATION's manual test finds a platform where
   one key press runs a command twice, the flag is the fallback, applied through §5.

From the second review (v1.2), adopted in part or replaced by another fix:

4. **A shared test-fixture builder of project analyses in core-workspace.** It would be contract code on top of the
   not yet implemented tokenizers, parsers and converter adapter, so the contracts step could not test it. The
   analyzer's conventions are documented on `ProjectAnalysis` instead, and WP-LANG builds its analyses by them.
5. **New shared descriptor fixtures for the resolver's edge cases.** Rule 8 now allows test-local descriptors in
   WP-WORKSPACE's reader and resolver unit tests; the shared descriptors remain the end-to-end and CLI scenarios.
6. **`DocumentSession.setSemanticMode`, driven by the workspace when a file's role changes.** Workspace sessions are
   always syntax-only (decision 28), so the mode never depends on a changing role.
7. **An opaque `TextVersion(lineage, number)` replacing every `version: Long`.** The lineage became a field of
   `TextSnapshot` and `Location` (decision 26); tokens, parse outcomes and diagnostics keep plain versions, which are
   only compared within one session.
8. **`openFind(mode, initialQuery)`.** Seeding the query from the selection is `FindModel.setQuery` before
   `openFind` (WP-EDITOR-VIEW's `EditorUiPort.openFind`), so the overlay state holds no find logic.
9. **A separate `navigateAt(offset)` intent.** `CodeEditorState.goToDefinition(offset)` is that intent.
10. **`LayoutState` writing through the `SettingsRepository` itself.** The mediator persists it (decision 31), so the
    sub-state stays a plain holder without I/O (decision 24).
11. **`EditorTabsState.move` and `ProjectTreeState.reveal` internal.** They stay public: reordering tabs by drag and
    revealing a tree node are UI intents without coordination.
12. **An `IdeWindow` composable for headless tests.** Compose's `Window` and `MenuBar` need a running application;
    `IdeApplication.prepare` makes all window-free wiring testable (decision 34), `Workbench(state)` is tested by
    WP-WORKBENCH, the window itself by INTEGRATION's launch smoke and the manual test.
13. **An `EditorModelsFactory` parameter of `CodeEditorState(…)`.** Recorded as an exception in decision 24.
14. **The project analysis producing `TypeInfo`.** That would move core-lang types into core-workspace; `TypeCatalog`
    memoizes its conversion per `ContextAnalysis` instead (decision 19).
15. **Renaming `EditorPopupModel`.** It is a role interface (the popups' view of the editor state) and keeps its role
    name (decision 32).
16. **A `title` member on `EditorAction`.** Presentation data would become an abstract member of every action class;
    `EditorActionTitles` keeps the titles next to the ids (decision 33).

From the third review (v1.3), adopted in part or replaced by another fix:

17. **`mapRange` callable only on the writer thread.** Mapping is thread-safe instead (decision 35), because the
    diagnostics merger and the highlight pipeline run on background dispatchers.
18. **A `char` field on `KeyStroke` with character-based bindings.** Replaced by layout-aware punctuation in the key
    adapter (decision 41): no change to the stroke type, the keymaps or the registry, and no data-class equality trap.
19. **`EditorStateFactory` and `EditorTabsState` returning `EditorHandle`.** The editor area must render the editor, so
    they keep `CodeEditorState`; the read-only sub-states and the mediator's collaborators use `EditorHandle`
    (decision 36).
20. **An INFO for conversions skipped because of a broken metamodel.** Not added: it would repeat what the metamodel's
    syntax errors already say and need a new code (decision 44).
21. **Intents serialized per path.** All intent flows run one at a time instead (decision 42): simpler, and the
    dialogs they wait for are modal anyway. (Decision 47 adds coalescing of repeated close requests of one tab and
    makes the dialogs modal for menu commands too.)
22. **`TabStops` in core-text.** It lives in `ui.editor.model.caret` (decision 40); core-text keeps code-unit columns.
23. **A `DISCARD` value of `ConflictResolution`.** `RELOAD` keeps its name with the documented meaning "take the disk
    state", and the result type tells the caller what happened (decision 43).
24. **A pure fraction ↔ dp helper in the workbench.** The size-based `SplitPane` makes it unnecessary (decision 46).

## 8. Open questions for the owner

1. **A JSON editor for `*.kenv.json`.** Phase 1 opens descriptors in the system's default application (decision 14);
   should a later phase add a JSON document kind with highlighting and in-editor descriptor diagnostics?
2. **Implicit contexts next to descriptors** (decision 13 above): directory-level coverage was chosen; the alternative
   is that every karpfen file not named by any descriptor joins an implicit context of its directory.
3. **dsl-tools console output in the IDE.** The converters `println` while parsing (and, for broken text, which the IDE
   no longer converts, ANTLR writes to `System.err`); the CLI redirects both streams, the IDE currently lets the
   `println`s reach the console. Redirect them to a logger in the app as well, or wait for dsl-tools to adopt SLF4J
   (proposal §5.1 item 6)?

The former question "menu accelerators vs. editor keymap" is resolved by decision 15.
