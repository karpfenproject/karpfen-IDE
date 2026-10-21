# P1 — Status report (text editing)

Status: v1.0, 2026-10-21. Written when phase 1 closed, for the owner. Verified on branch `impl/p1-text-editing` at
`8fd0567` ("Merge fixes for core (review round 2)"). Before this report, the branch held 167 commits on top of
`main`; it has not been pushed. Every work package and every review fix branch is merged, no worktree is
registered, and no `TODO(` is left.
The phase's contract is [p1-text-editing-spec.md](p1-text-editing-spec.md). Ownership and the refining decisions are
in [p1-work-packages.md](p1-work-packages.md), and the manual smoke test is
[p1-manual-test.md](p1-manual-test.md).

**Result: phase 1 is complete.** All seven items of the definition of done (spec §11) were checked again on the final
code and are met (§4). Item 7 is the manual test document; running it on a real Mac is the owner's step. Two review
rounds confirmed 53 findings, and all 53 are fixed (§6). The verification found no new defect.

## 1. What phase 1 delivers

The IDE opens a folder of karpfen files and edits KMeta (`.kmeta`), KModel (`.kmodel`) and KStates (`.kstates`) in a
custom editor, with language support built on the unchanged karpfen-dsl-tools.

**Project and files**
- Open Folder…, Open Recent (up to 10 folders), or a folder given on the command line. With no folder, an empty state
  offers Open Folder… and the recent folders. A missing or unreadable folder keeps the open workspace.
- The Project tree shows file-kind icons and opens files on double-click or Enter. Its context menu offers new
  KMeta, KModel, KStates and environment files from templates (filled from the context's types, objects and attached
  types), Rename (also case-only renames on macOS), Delete (to the trash where supported, with confirmation) and
  Reveal in Finder/Explorer. Environment descriptors (`*.kenv.json`) and other files open in the system's default
  application.
- Modeling contexts: every directory with karpfen files forms an implicit context (its metamodel, `EVENTS.kmeta`,
  models and state machines). An environment descriptor (`*.kenv.json`) names the files of a context explicitly; its
  findings (invalid JSON, unknown key, missing file, wrong file language, repeated key) have positions in the
  descriptor.
- Text files on disk are the single source of truth. A file watcher picks up external changes, also below symbolic
  links. A clean document reloads as one undoable step. A dirty one shows a banner with Reload / Keep mine, or Close /
  Keep mine when the file was deleted.
- Saving is atomic: the content is forced to storage before the move. The line separator and the byte order mark are
  restored. A read-only file is refused ("the file is read-only"), not replaced. Save, Save All and the save in the
  close and exit dialogs ask before overwriting an unresolved external change.

**Editor**
- Lexical and semantic highlighting, including the embedded Python of `EVAL` bodies (`$(path)`, `@macro`, Python
  keywords, strings, numbers, comments). A `//` inside EVAL is flagged, because the karpfen lexer treats it as a
  comment.
- Live diagnostics: lexer and parser errors per keystroke, plus the dsl-tools analysis of each modeling context
  (debounced, cancellable). They appear as wavy and dotted underlines, gutter markers and a hover tooltip.
- Completion for all three languages: structural keywords and snippets, types, properties and relations of the
  enclosing object's type, state names, macro names, event names, and model paths resolved segment by segment
  (`->`, `$(` in EVAL). Prefix, then word-boundary, then subsequence filtering; at most 12 rows.
- Go to definition by Cmd/Ctrl+click or Navigate ▸ Go to Definition, also across files (types into the context's
  `.kmeta`/`EVENTS.kmeta`, keys into the metamodel). A hover after 600 ms shows types with descriptions and members,
  property signatures, state hierarchy paths, macro signatures and model-path types. Matching brackets are boxed.
- Editing: caret movement by character, word, line, page and document, with smart Home. Selection, plus
  double-click for a word and triple-click for a line. Auto-indent, auto-close of `"`, `(` and `{` with over-typing,
  smart Backspace. Delete word or line, duplicate, move a line up or down, indent and outdent. Toggle comment uses `//`,
  or `#` inside an EVAL body. Copy and cut take the whole line when nothing is selected, and paste puts such a line
  above the caret's line. Every action is one undo step, and undo/redo restores the carets.
- Find/replace with case, whole-word and regex options, an `n/m` counter and Find Next/Previous. The search runs off
  the UI thread with a time budget, so a catastrophic regex ends with "Search took too long". Go to line accepts
  `line[:col]`.
- Text input works on every keyboard layout: Option combinations of the German layout, dead keys, the accent menu
  and IME. Rendering is virtualized, with tab stops every 4 columns.

**Workbench and application**
- Editor tabs show a dirty dot and a conflict marker. The Outline navigates on click and Enter and highlights the
  node that contains the caret. Problems are grouped by file, with an error/warning filter, and a double-click
  navigates. The status bar shows `Ln, Col`, the selection length, the language, the line separator, `UTF-8`, the
  error and warning counts and "Analyzing…".
- Dialogs are modal for commands and for the keyboard focus: unsaved changes on close and exit, overwrite
  confirmation, errors.
- Native macOS menu bar (File, Edit, View, Navigate), built from the same commands as the keymap. Quit, logout and the
  window's close button all run the unsaved-changes flow.
- The light or dark theme, the window bounds and the tool-window sizes persist in `~/.karpfen-ide/settings.json`
  (moved by `KARPFEN_IDE_HOME`). A corrupt or non-UTF-8 settings file is backed up and reset. Uncaught exceptions are
  logged to `~/.karpfen-ide/logs/ide.log` and shown in a dialog.

**Command line:** `karpfen validate <dir> [--format text|json]` runs the same problems rule as the IDE (syntax of
every file, dsl-tools analysis of every context, descriptors, unreadable files and directories) and prints
`path:line:col: severity: message [code]`. Exit codes: 0 when there are no errors, 1 when there are errors, 2 for
usage errors, 3 for a failure of the validation itself. The output is UTF-8 whatever the console's encoding, and what
dsl-tools prints is suppressed.

## 2. How to run and test

Requirements and build modes are in the [README](../README.md#build-and-run) (JDK 17+ for Gradle, JDK 21 toolchain).

```bash
./gradlew build                                   # compile, 1,572 tests, architecture and module-dependency checks
cp -R core/core-document/src/testFixtures/resources/examples /tmp/karpfen-examples
./gradlew :app:ide:run --args=/tmp/karpfen-examples          # the IDE on a copy of the examples
./gradlew :tools:cli:installDist
tools/cli/build/install/karpfen/bin/karpfen validate /tmp/karpfen-examples   # exit 0, no output
```

Launch smoke test, with a throw-away settings and log directory:

```bash
KARPFEN_IDE_HOME=/tmp/karpfen-home KARPFEN_IDE_EXIT_AFTER_MS=8000 ./gradlew :app:ide:run --args=/tmp/karpfen-examples
grep ERROR /tmp/karpfen-home/logs/ide.log         # prints nothing
```

Before you use phase 1 for real work, run the **10-minute manual smoke test in [p1-manual-test.md](p1-manual-test.md)**
on your Mac with the German input source. It covers what headless tests cannot: the native menu bar, typing with
Option combinations, dead keys and the accent menu, real focus and mouse handling, the file watcher and quitting.
Each module's test report is at `<module>/build/reports/tests/test/index.html`. The measured budgets are JUnit report
entries in `<module>/build/test-results/test/*.xml`.

## 3. Verification environment

macOS (Darwin 25.6), OpenJDK 21, Gradle 9.8.2, Kotlin 2.4.21, Compose Multiplatform 1.12.2, karpfen-dsl-tools
`1.0-SNAPSHOT` (artifact from `~/.m2`). For composite mode, a source checkout at `859a95d`, the commit CI pins. The
measurements in §5 are from one run on this machine and vary between runs.

## 4. Definition of done (spec §11), re-verified on `8fd0567`

| # | Item | Result | Evidence |
|---|---|---|---|
| 1 | `./gradlew build` green in artifact and composite mode, including architecture tests | met | Artifact mode: `./gradlew test --rerun build` re-ran all 13 test tasks: BUILD SUCCESSFUL, 1,572 tests, 0 failed, 1 skipped (see §5). Composite mode: `./gradlew build` in a local clone of `8fd0567` with a clone of dsl-tools `859a95d` as a sibling ("composite build from …"): BUILD SUCCESSFUL, 1,572 tests, 0 failed. Every module that depends on dsl-tools ran its tests against the dsl-tools built from source; the modules independent of it came from the build cache. The original checkouts were not modified. `verifyModuleDependencies` and the 31 architecture tests (Konsist) are part of `check`. |
| 2 | Every example tokenizes and parses with zero LEXER/PARSER diagnostics; every context analyzes with zero DSL_TOOLS errors | met | `TokenizerExamplesTest`: all 21 example files have empty diagnostics. `ExamplesSyntaxTest` "every example parses without syntax diagnostics". `DefaultProjectAnalyzerTest` "every example context analyzes with zero DSL_TOOLS errors and converts every file" (21 files in 6 contexts). `WorkspaceAnalysisTest` "the examples folder scans, resolves and analyzes with zero findings". `karpfen validate --format json` on a copy: `errorCount 0, warningCount 0, problems []`. |
| 3 | Tokenizer, parser, outline, index, highlight, completion, definition, hover never throw on 10,000 mutations and on every prefix of a KStates example | met | Tokenizer: 10,000 mutations of each of the 21 examples (seed 20261020), plus all 10,476 prefixes of `cleaning_robot.kstates`. Parser, outline and index: 10,000 mutations per language, plus every prefix. Language services (every feature at 7 offsets per document): 10,000 mutated documents per language, plus every prefix. Beyond the spec: 600 mutated contexts through the project analyzer, 6,624 mutated descriptors, mutated texts through the editor state and the rendered `CodeEditor`, and 48 `karpfen validate` runs on mutated contexts. |
| 4 | Editor model and Compose UI tests of §7 pass headless; benchmark numbers reported | met | ui-editor 440, ui-foundation 153, ui-workbench 172 and app/ide 77 tests pass headless. These include the `CodeEditor` view tests (keyboard, text input, mouse, scrolling, focus traversal, rendering), the popups, `CodeEditor` together with the popups end to end, the workbench end to end over the examples, and key routing through the composition root. Benchmark: a 10,016-line KStates document scrolled to the middle averages **8.82 ms per frame** (budget 50 ms). |
| 5 | `karpfen validate <examples dir>` exits 0 and prints no errors | met | `:tools:cli:installDist`, then `karpfen validate` on a temp copy of the examples: exit 0, no output. On the single-robot files plus `invalid_json.kenv.json`: exit 1, `invalid_json.kenv.json:3:11: error: Invalid environment descriptor: … [descriptor-invalid]`. With `unknown_key_and_missing_file.kenv.json` instead: exit 1 (`descriptor-missing-file` error at 4:13, `descriptor-unknown-key` warning at 8:4). Usage errors (`validate ""`, a missing directory) exit 2. |
| 6 | Launch smoke test exits 0 with no ERROR in the log | met | `KARPFEN_IDE_HOME=<temp> KARPFEN_IDE_EXIT_AFTER_MS=8000 ./gradlew :app:ide:run --args=<temp copy of the examples>`: exit 0. `ide.log` holds exactly two INFO lines (start, "Exiting after 8s") and no WARN or ERROR. The console shows no exception, only dsl-tools' own model dumps (open question 3). The spec names 6000 ms; 8000 ms gives the window more time. |
| 7 | `docs/p1-manual-test.md` lists a 10-minute manual smoke test | met (document) | v1.0 covers setup, start, typing on the German layout (Option combinations, dead keys, accent menu), toggle comment, completion, navigation, problems, undo/redo, save/close, external changes, quit and restart. **No run is recorded yet.** |

The build enforces part of the design rules of spec §1a: Konsist rules for foreign-API confinement, Compose-free
state packages, UI layering, no package cycles and one home for the lexical facts, plus module-dependency checks. The
other rules (single responsibility, constructor injection, logic-free composables, …) were checked in the review
rounds, and several confirmed findings were design findings (§6). No main source file is longer than 303 lines; the
longest is `CodeEditorState.kt`, an interface file that is mostly KDoc.

## 5. Tests and measured budgets

Fresh run of every test task on `8fd0567` (JUnit reports; each parameterized invocation counts as a test). The
included build `build-logic` is not selected by `test --rerun`; its 13 tests are up to date with their inputs and came
from the build cache in the composite run.

| Module | Test reports | Tests |
|---|---:|---:|
| core/core-text | 17 | 111 |
| core/core-document | 38 | 225 |
| core/core-workspace | 23 | 170 |
| core/core-lang | 22 | 136 |
| ui/ui-foundation | 22 | 153 |
| ui/ui-editor | 64 | 440 |
| ui/ui-workbench | 33 | 172 (1 skipped) |
| app/ide | 11 | 77 |
| tools/cli | 9 | 41 |
| architecture-tests | 8 | 31 |
| build-logic | 2 | 13 |
| phase-0 stubs (bridge-api, notation-diagram, runtime-protocol) | 3 | 3 |
| **Total** | **252** | **1,572** (1,571 passed, 1 skipped, 0 failed) |

The skipped test (`DesktopFileOperationsTest`, a case-only rename onto another file of that name) assumes a
case-sensitive file system. On macOS it is skipped by design; the case-insensitive path is tested.

Budgets of the spec (asserted with 5x slack, measured value reported):

| Budget | Spec | Measured |
|---|---:|---:|
| Tokenize a 5,000-line KStates document (5,007 lines, 19,037 tokens) | < 30 ms | 14.8 ms |
| Parse it | < 150 ms | 6.5 ms |
| Editor frame, 10,016-line KStates scrolled to the middle, after warm-up | < 50 ms | 8.8 ms |

Further guards of the project (5x slack unless the row says otherwise; — means measured and reported only):

| Measurement | Guard | Measured |
|---|---:|---:|
| Keystroke (apply + undo record) in 5,000 lines | 500 µs | 99 µs |
| Undo / redo of one step in 5,000 lines | 1 ms | 108 µs / 122 µs |
| `mapRange` across 1,000 versions | 400 µs | 72 µs |
| `mapRange` across one version of 10,000 edits (fixed in round 2) | 5 µs | 0.35 µs |
| Highlight 5,007 KStates lines | 30 ms | 9.9 ms |
| Completion on 5,007 lines: model path / keywords / state names | 30 ms | 0.31 / 0.31 / 1.7 ms |
| Hover (model path) / definition (state) on 5,007 lines | 30 ms | 0.86 / 0.08 ms |
| Find in 5,000 lines: plain / regex whole-word | 20 / 30 ms | 1.0 / 6.4 ms |
| Expand 914 regex replacements | 30 ms | 3.5 ms |
| Frame with 8,000 find matches on one long line, bar open vs closed (fixed in round 2) | open < 3 × closed + 10 ms | 18.4 vs 12.1 ms |
| Completion list with 5,000 items: open and first frame | — | 24 ms |
| TreeView with 10,100 expanded rows: first frame / reveal last row (20 rows composed) | 1 s / 200 ms | 160 ms / 51 ms |
| Workspace start + analysis of the examples (first run) | — | 271 ms |
| Analysis of all 6 example contexts, warm | — | 99 ms |

## 6. Review rounds

The contracts step had three reviews of its own; they are recorded in
[p1-work-packages.md §6–§7](p1-work-packages.md#6-decisions-of-the-contracts-step-that-refine-the-spec). After
integration, the implemented phase went through two review rounds. In each round, every candidate finding was
verified, then confirmed or rejected. The confirmed ones were fixed in three groups (core, editor, shell) on fix
branches and merged with a green build. Fixes that change a rule or a contract are recorded as decisions 47–70 in
[p1-work-packages.md §6](p1-work-packages.md#6-decisions-of-the-contracts-step-that-refine-the-spec).

| Round | Confirmed | of them major | Fixed | Not fixed | Rejected |
|---|---:|---:|---:|---:|---:|
| 1 | 27 | 6 | 27 | 0 | 18 |
| 2 | 26 | 6 | 26 | 0 | 5 |
| **Total** | **53** | **12** | **53** | **0** | **23** |

**No confirmed finding was left unfixed.**

### Round 1 (merges `d374047` shell, `20dc698` core, `dc24442` editor)

| Finding | Severity | Fix (commit, decision) |
|---|---|---|
| "Keep mine" left the save point in place, so a kept buffer reported clean and was discarded without a prompt | major | `a7706ab`, 48 |
| A case-only rename on macOS did nothing on disk, but the file reopened under the new spelling (one file in two documents) | major | `19f7a73`, 47 |
| Find next/previous/replace ran an uncancellable regex search on the UI thread | major | `7dc0736`, 54 |
| An outdated completion result was re-requested at the new caret, so the popup opened after a space or Enter | major | `663c15e`, 56 |
| Shift+Tab (and Tab over blank lines) moved the keyboard focus out of the editor | major | `d13a82e`, 56 |
| Save All and Save in the close/exit dialog silently overwrote an unresolved external change | major | `91c1422`, 47 |
| An invisible completion session still captured Enter/Tab/Up/Down | minor | `a294b8b`, 56 |
| Copying or cutting a whole line without a selection, then pasting, split the target line | minor | `e96e3df`, 55 |
| Startup deadlock when a dialog was raised while opening the command-line folder | minor | `5b94176`, 47 |
| Two close requests for a dirty tab showed the unsaved-changes dialog twice | minor | `9aea27b`, 47 |
| The watch debounce could drop a file change that arrived exactly as the quiet period ended | minor | `a5e3679` |
| `FindModel` kept its real query private, so callers read an out-of-date copy | minor | `7dc0736`, 54 |
| Typing during a save's write left the save point at the previous save | minor | `228f4cb`, 49 |
| FILE findings (malformed UTF-8, mixed separators) disappeared after 1,000 edits | minor | `2cedc7a`, 50 |
| Directories whose files a descriptor names still formed implicit contexts (false "not converted" INFOs) | minor | `a5eea20`, 51 |
| Type errors inside nested descriptor objects were reported on the whole document | minor | `bce84fc` |
| Duplicate descriptor keys: the context used the last value, the missing-file check the first | minor | `76aa8d9`, 52 |
| The atomic save renamed the temp file over the target without flushing it to disk | minor | `009291e` |
| Cut deleted the text even when the clipboard write failed | minor | `e96e3df`, 55 |
| The hover tooltip disappeared as soon as the pointer moved onto it | minor | `82455c0`, 56 |
| Caret reveal hid the caret in viewports shorter than 4 lines | minor | `dfb1094`, 56 |
| Workbench dialogs did not block menu shortcuts | minor | `d5cab84`, 47 |
| A `settings.json` that is not valid UTF-8 was overwritten without a backup | minor | `7ddea47`, 47 |
| `karpfen validate` passed (exit 0) when files or directories it could not read contained errors | minor | `315ac0a`, 47 (replaced by 67) |
| Opening a recent folder that no longer exists closed the open workspace and kept the stale entry | minor | `6767e8d`, 47 |
| `WorkbenchServices.languages` was an unused required dependency and a second language registry | minor | `6c0e584`, 47 |
| Lexical rules and vocabularies were re-declared in several modules | minor | `3b82eb7`, 53 |

### Round 2 (merges `afa371c` editor, `ac7dd40` shell, `8fd0567` core)

| Finding | Severity | Fix (commit, decision) |
|---|---|---|
| dsl-tools errors were placed on the first matching symbol in the file, often a different, valid one | major | `f512ad1`, 65 |
| Enter/Tab after a block keyword plus `{` accepted the completion snippet and put the block on one line | major | `71d1469`, 57 |
| Commenting out a block with a multi-line EVAL produced invalid KStates; toggling again double-commented it | major | `6857fb4`, 58 |
| Modal dialogs did not keep the keyboard focus (Tab left the dialog, keys edited the document behind it) | major | `c092879`, 62 |
| Outline: clicking the node that contains the caret did nothing; arrow keys navigated and took the focus away | major | `c436f02`, 63 |
| The file watcher never followed symbolic links (nothing watched when the opened folder is itself a link) | major | `e91073f` |
| A descriptor role naming a file of the wrong language silently turned off the context's analysis | minor | `d99afb6`, 66 |
| Saving silently overwrote a read-only file | minor | `eab133c` |
| The round-1 hidden-completion fix closed completions whose caret was being revealed | minor | `1dc1c98`, 59 |
| Find Next/Previous typed in the find bar moved the focus into the document | minor | `ea21cb2`, 62 |
| Unreadable files and directories were detected only in the CLI, not in the IDE | minor | `9ec6451`, 67 |
| Opening externally a file that was just deleted showed "Unexpected error" and logged at ERROR | minor | `74dfdb3`, 64 |
| `karpfen validate` wrote `?` for non-ASCII characters when the locale was not UTF-8 | minor | `fbd04f9`, 64 |
| `karpfen validate ""` validated the working directory | minor | `86a2338`, 64 |
| The folder chooser never resumed when the dialog threw, so Save, Close Tab, Open and Exit waited forever | minor | `474e0a5`, 64 |
| The hover tooltip kept stale content after edits not made by typing (reload, Replace All, menu commands) | minor | `e97da77`, 60 |
| The KStates block-kind rule was implemented twice, in two modules | minor | `e0f6282`, 68 |
| The one-home rule for lexical facts was incomplete (model-path syntax, operations, member keywords) | minor | `380c089`, 69 |
| The project tree's open/toggle policy lived in the composable | minor | `d8722a3`, 63 |
| The clipboard was not injectable through the composition root; every editor had its own | minor | `37498e3`, 64 |
| The find replacement text lived in the composition and was lost on a tab switch | minor | `f3f80fb`, 60 |
| `DocumentSaver` re-implemented the loading rule to predict the saved file's disk state | minor | `f093f3f`, 70 |
| The completion list's page size was a copy of the popup's row count | minor | `daf6826`, 60 |
| With a syntax error, every keystroke mapped outline ranges through up to 1,000 versions on the UI thread | minor | `85e4aac`, 64 |
| Offset mapping was linear in a version's edits, so bulk edits stalled highlighting and frames | minor | `72be11b` |
| Painting find matches cost O(matches × line length) per frame | minor | `b8222b7`, 61 |

### Rejected candidates

Verification did not confirm these candidates, so they were not changed. The review history keeps only their keys, so
the reasons are not recorded here. Round 1: `failed-delete-rename-loses-edits`,
`deleted-event-trusted-without-disk-read`, `find-previous-goes-forward`, `go-to-line-column-mismatch`,
`wide-char-geometry-drift`, `string-literal-rule-duplicates-tokenizer`, `workbench-exposes-workspace`,
`navigation-flow-drives-caret`, `comment-syntax-hard-coded`, `symbol-lookups-duplicated`,
`decoration-mapping-in-painters`, `analysis-reparses-unopened-files`, `focus-independent-list-in-workbench`,
`log-path-global-property`, `template-context-builder-misplaced`, `redundant-tokenization-per-keystroke`,
`unbounded-undo-history`, `find-runs-while-bar-closed`. Round 2: `descriptor-required-keys-optional`,
`dialog-enter-ignores-focused-button`, `completion-visibility-rule-in-composable`, `type-name-stringly-typed`,
`icon-mappings-duplicated`.

## 7. Known issues and limitations

Nothing found during this verification needs a fix before phase 1 is used. The following are known and accepted.

**Not verified by automation**
- No run of the manual smoke test ([p1-manual-test.md](p1-manual-test.md)) is recorded yet. The headless tests
  cannot cover the native macOS menu bar, real keyboard layouts (Option combinations, dead keys, the accent menu,
  IME), real focus and mouse handling, or quitting.
- The branch has not been pushed, so CI (composite mode at the pinned dsl-tools commit) has not run on it. Both
  modes were built locally (§4, item 1).
- Everything was run on macOS only. The keymap and the desktop adapters are platform-aware (Ctrl instead of Cmd,
  Explorer instead of Finder), but no Windows or Linux desktop was exercised.

**Behaviour to know about**
- On macOS the JDK's file watcher polls, so an external change appears after up to about 10 seconds.
- Ctrl+Space (completion) never reaches the IDE when macOS uses it to switch input sources. The completion also
  opens automatically as you type.
- The dsl-tools converters `println` their models while the IDE analyzes. That output reaches the terminal that
  started the IDE, not the log (open question 3). The CLI suppresses it.
- dsl-tools reports semantic errors without positions. The IDE places each one on the key it names, using the names
  quoted in the message (decision 65). The placement is a heuristic: a message that quotes no name found in the file
  falls back to the file's first token (spec §3.3).
- Karpfen files in a descriptor's directory that the descriptor does not name are loose files: they are
  syntax-checked, never converted (open question 2). A conversion that needs a metamodel that does not convert is
  skipped without an extra message; the metamodel's own syntax errors explain it (decision 44).
- The View menu offers Light and Dark theme. The default follows the system, but there is no menu item to return to
  it once a theme has been picked: set `"theme": "SYSTEM"` in `settings.json`. The editor font size can only be set
  in `settings.json`.
- The undo history of an open document has no step limit; the offset-mapping history keeps 1,000 versions.

**Implemented below the UI, not exposed yet**
- `LanguageServices.references` (find usages) works but has no UI command. The parser computes folding ranges, but
  the editor does not fold.

**Out of scope for phase 1 (spec §0)**
- Diagrams, the runtime and bridges (later phases, still stubs), LSP, rename refactoring, formatting, multi-caret UI,
  split editors, and IDE-side semantic rules (semantic analysis stays in karpfen-dsl-tools). Environment descriptors
  have no editor of their own (open question 1).

**Build**
- Every build that configures the project (no configuration-cache hit) prints Gradle's "Deprecated Gradle features
  were used … incompatible with Gradle 10". The cause is `Configuration.setVisible(boolean)` while configuring
  `:app:ide`, called by a plugin applied there, not by the project's build scripts; Gradle 11 removes it. Revisit
  when the Compose Gradle plugin is updated.
- One test is skipped on case-insensitive file systems (§5).

## 8. Open questions for the owner

From [p1-work-packages.md §8](p1-work-packages.md#8-open-questions-for-the-owner), with what phase 1 now does:

1. **A JSON editor for `*.kenv.json`.** Phase 1 opens descriptors in the system's default application (decision 14).
   Their findings appear with positions in Problems and in `karpfen validate`, but not in an editor. Should a later
   phase add a JSON document kind with highlighting and in-editor descriptor diagnostics?
2. **Implicit contexts next to descriptors.** Decision 51 narrowed this: a file named by a descriptor is covered
   wherever it lies, and a directory left without unnamed karpfen files forms no implicit context. The descriptor's
   own directory is still covered as a whole, so karpfen files there that it does not name are loose (syntax only).
   Keep this directory-level coverage? The alternative is that every karpfen file not named by any descriptor joins an
   implicit context of its directory.
3. **dsl-tools console output in the IDE.** Still open, and confirmed in the launch smoke test: the converters'
   `println`s reach the console that started the IDE, while the IDE log stays clean. Redirect them to a logger in the
   app as well (the CLI already redirects `System.out` and `System.err`), or wait for dsl-tools to adopt SLF4J
   (proposal §5.1 item 6)?

## 9. Next step

Phase 2 (runtime: the Docker-based runtime host, deploy/run/stop, event injector, model mirror; see the
[roadmap](architecture-proposal.md#7-roadmap)) has not been started. It needs Docker and the karpfen runtime and waits
for your go-ahead. Before that, please run the manual smoke test and answer the open questions above.
