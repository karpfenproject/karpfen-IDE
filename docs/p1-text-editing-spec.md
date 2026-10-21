# P1 — Text editing: implementation specification

Status: v1.2, 2026-10-20 (v1.1 added §1a design principles requested by the owner; v1.2 names the CLI `karpfen`
like its start script). Companion to `docs/architecture-proposal.md` (v0.3, accepted). This document is the
contract for the phase-1 work packages. Where it says MUST, tests must prove it.

The contracts step refined some signatures of the sketches below where they conflicted with §1a; the refinements and
the assignment of every file to a work package are in [p1-work-packages.md](p1-work-packages.md) (§6 lists the
refinements).

## 0. Scope

In scope (IDE side of roadmap phase P1): `core-text`, `core-document`, `core-workspace`, `core-lang`,
`ui-foundation`, `ui-editor`, `ui-workbench`, `app/ide`, and a `validate` command in `tools/cli`.

Out of scope: diagrams, runtime, bridges, LSP, rename refactoring, formatting/printing, multi-caret UI
(the data model allows it later), split editors, IDE-side semantic validation rules (decided: semantic
analysis belongs in karpfen-dsl-tools), any change to karpfen-dsl-tools.

**dsl-tools usage in P1.** karpfen-dsl-tools is used unchanged (`io.karpfen:karpfen-dsl-tools:1.0-SNAPSHOT`
or the composite build). Positions come from the ANTLR parse trees produced by the *generated parsers that
ship inside dsl-tools* (`kmeta.KmetaParser`, `kmodel.KmodelParser`, `kstates.KstatesParser` and their lexers),
invoked with IDE-owned error listeners. Semantic models come from the dsl-tools converters
(`dsl.textual.KmetaDSLConverter`, `KmodelDSLConverter`, `KstatesDSLConverter`). There is no second grammar or
parser in the IDE. All code that touches ANTLR or the generated packages lives in ONE internal package,
`io.karpfen.ide.core.document.antlr`, so it can later be replaced by dsl-tools' own SourceMap/ParseResult API.
A Konsist rule enforces this (only that package may import `org.antlr..`, `kmeta..`, `kmodel..`, `kstates..`).

## 1. Cross-cutting conventions

- **Offsets** are UTF-16 code-unit indices into the document `String`. Lines and columns are zero-based in
  APIs; the UI shows them one-based.
- **Line separators**: documents are normalized to `\n` in memory on load; the dominant original separator
  (`\n`, `\r\n`, `\r`) is remembered per document and restored on save. Mixed separators are normalized and
  reported once as an INFO diagnostic.
- **Encoding**: UTF-8. Malformed input is decoded with replacement characters and reported as a WARNING.
- **Threading**: `EditableText` is mutated from one thread only (the UI thread in the app; the test thread
  in tests). Snapshots are immutable and freely shareable. Background work reads snapshots only.
  Long-running work runs on `Dispatchers.Default` (CPU) or `Dispatchers.IO` (files) and is cancellable.
- **Reactive API**: state is exposed as `StateFlow`, events as `SharedFlow`. No callbacks-with-threads.
- **Logging**: SLF4J only. No `println` except CLI output. `logback.xml` lives only in `app/ide` and `tools/cli`.
- **Public API**: core/* and notation/* use explicit API mode; every public declaration has KDoc.
- **Errors**: library code never lets an exception from parsing/analysis escape to the UI; it becomes a
  `Diagnostic`. Programming errors (precondition violations) may throw `IllegalArgumentException`.
- **Determinism**: randomized tests use fixed seeds. Time is injected (`kotlin.time.TimeSource` or a
  `() -> Long` clock) wherever behaviour depends on time (undo coalescing, debounce in tests via
  `kotlinx-coroutines-test` virtual time).
- **No time-budget testing in test runs** (owner decision, 2026-10-22, [ADR 0011](adr/0011-time-budget-tests-are-not-run.md)):
  a test that compares a wall-clock duration with a limit carries the JUnit tag `budget` and is excluded from every
  test task, CI included. Machine load and sleep/wake cycles make the clock unreliable; performance tuning is a later
  task. The budgets below are targets that such tests document, not build gates.

## 1a. Design principles (owner requirement; reviewers check these explicitly)

The owner asked for a clean, object-oriented, modular design using common design methods and patterns, with modules
and classes built for **high cohesion and low coupling**. Concretely:

- **One responsibility per class, one theme per package.** No god classes (a class over ~300 lines or with more than
  one reason to change must be split). Packages are organized by feature inside a module (`syntax`, `outline`,
  `completion`, …), not by technical layer (`utils`, `helpers`, `managers`).
- **Program to interfaces at every seam between packages and modules** (Dependency Inversion). Consumers depend on
  interfaces owned by the providing module's API package; implementations are `internal` where possible. Construction
  happens through constructors (constructor injection) or small factories; no service locators, no mutable global
  state, no `object` singletons that hold state. A Kotlin `object` is only allowed for stateless constants or pure
  functions with no plausible alternative implementation.
- **Per-language variation uses Strategy + Abstract Factory**: a `LanguageSupport` per language (KMeta, KModel,
  KStates) bundles that language's tokenizer, parser adapter, and feature providers; a `LanguageRegistry` resolves it by
  `KarpfenLanguage` or file name. Adding a fourth language must not require editing existing language classes
  (Open/Closed).
- **Adapters isolate foreign APIs**: dsl-tools converters and the ANTLR parse trees are reached only through adapter
  classes in `io.karpfen.ide.core.document.antlr` (ANTLR) and `io.karpfen.ide.core.document.dsl` (converters); no other
  package sees ANTLR types or dsl-tools parser classes.
- **Facades for subsystems**: `DocumentSession` (per document), `Workspace` (per folder), `LanguageServices` (per
  workspace) are small facades over cohesive collaborators; they coordinate and delegate rather than implement.
- **Editor behaviour uses the Command pattern**: every editing/navigation operation is an `EditorAction` object
  (`id`, `execute(context)`), registered in an action registry; keymaps map shortcuts to action ids; menus and context
  menus reference the same ids. Undo uses transactions with caret mementos (Memento).
- **Observer via Kotlin flows**: state changes are published as `StateFlow`/`SharedFlow`; no listener interfaces with
  manual registration unless a foreign API demands it.
- **Immutable value objects** for everything that crosses a thread or module boundary (snapshots, tokens, diagnostics,
  outline nodes, completion items); mutation is confined to clearly named state holders.
- **UI follows unidirectional data flow**: composables render immutable UI state and emit intents; state holders
  (plain Kotlin classes, unit-testable without Compose) own logic. Composables contain no business logic.
- **Dependency direction inside modules** is acyclic: API package ← implementation packages; cycles between packages
  are forbidden (checked by Konsist where feasible).
- **Testability is a design criterion**: time, dispatchers, file system roots and settings locations are injected;
  every collaborator can be replaced by a fake in tests.

## 2. core-text (`io.karpfen.ide.core.text`)

Types (sketch; names are binding, details may be refined in the contracts step):

```kotlin
data class TextRange(val start: Int, val end: Int)          // half-open; 0 <= start <= end
data class TextPosition(val line: Int, val column: Int)
data class TextEdit(val range: TextRange, val newText: String)   // + insert/delete/replace factories
enum class LineSeparator(val chars: String) { LF("\n"), CRLF("\r\n"), CR("\r") }
class TextSnapshot(val text: String, val version: Long)      // lineIndex (lazy), substring, lineText, positionOf, offsetOf
class LineIndex(text: String)                                 // lineCount, lineStart, lineEnd (excl. \n), lineOf, positionOf, offsetOf(clamp)
sealed interface EditOrigin                                   // Typing, Paste, Cut, Undo, Redo, Command(name), External, Programmatic(name)
data class CaretMemento(val anchor: Int, val caret: Int)
data class TextChange(val before: TextSnapshot, val after: TextSnapshot, val edits: List<TextEdit>, val origin: EditOrigin)
class EditableText(initialText: String, clock: () -> Long = …) {
    val snapshot: StateFlow<TextSnapshot>
    val changes: SharedFlow<TextChange>
    fun apply(edits: List<TextEdit>, origin: EditOrigin, caretsBefore: List<CaretMemento>? = null, caretsAfter: List<CaretMemento>? = null): TextChange
    fun replaceAll(newText: String, origin: EditOrigin): TextChange
    fun mapOffset(offset: Int, fromVersion: Long, toVersion: Long = current, bias: Bias = Bias.RIGHT): Int?   // null if history evicted
    val undo: UndoManager
}
class UndoManager { canUndo/canRedo: StateFlow<Boolean>; undo(): UndoResult?; redo(): UndoResult?; group(name) { … }; markSavePoint(); isAtSavePoint: StateFlow<Boolean>; clear() }
```

Semantics (MUST):
- `apply` takes edits in pre-edit coordinates, sorted or unsorted, non-overlapping (two insertions at the same
  offset are allowed and applied in list order), applies them atomically, and produces exactly one new version
  (`version + 1`). Empty edit lists are a no-op (no new version).
- `UndoResult` carries the carets to restore. Undo of an edit restores the text byte-for-byte.
- Coalescing: consecutive `Typing` single-character insertions contiguous with the previous one, or consecutive
  single-character deletions contiguous with the previous one, within 1000 ms, merge into one undo step; a typed
  newline or whitespace-after-word starts a new step; `Paste`, `Cut`, `Command`, `External` never coalesce.
  `group { }` makes any sequence of applies one undo step.
- `isAtSavePoint` is true iff the current state equals the state at `markSavePoint()` reached through undo/redo
  (used for the dirty flag).
- `mapOffset` keeps a bounded history (≥ 1000 versions) and maps offsets across versions with left/right bias.

Tests (MUST): random edit sequences (≥ 10,000 ops, fixed seeds) checked against a `StringBuilder` reference;
undo-all returns the initial text, redo-all the final; coalescing rules; `mapOffset` vs reference; `LineIndex`
edge cases (empty text, trailing newline, only newlines, very long line).

## 3. core-document (`io.karpfen.ide.core.document`)

### 3.1 Languages and tokens

```kotlin
enum class KarpfenLanguage(val extension: String, val displayName: String) { KMETA, KMODEL, KSTATES; companion fun fromFileName(name: String): KarpfenLanguage? }
enum class TokenKind { KEYWORD, OPERATION, STRING, NUMBER, IDENTIFIER, PUNCTUATION, OPERATOR, COMMENT,
                       EMBEDDED_CODE, EMBEDDED_KEYWORD, EMBEDDED_COMMENT, EMBEDDED_STRING, EMBEDDED_NUMBER,
                       PATH_REFERENCE, MACRO_REFERENCE, ERROR }
data class SyntaxToken(val range: TextRange, val kind: TokenKind)
data class TokenizeResult(val version: Long, val tokens: List<SyntaxToken>, val diagnostics: List<Diagnostic>)
interface Tokenizer { fun tokenize(snapshot: TextSnapshot): TokenizeResult }        // one implementation per language
interface LanguageSupport { val language: KarpfenLanguage; val tokenizer: Tokenizer; val parser: DocumentParser }
interface LanguageRegistry { fun support(language: KarpfenLanguage): LanguageSupport; fun languageOf(fileName: String): KarpfenLanguage? }
```

Rules (MUST):
- Uses the dsl-tools lexers with error listeners removed and an IDE collector added. Never throws.
- Tokens are sorted, non-overlapping, and every non-whitespace character of the input belongs to exactly one token.
- KMeta/KModel lexers skip comments; comments are recovered from the gaps between tokens (a gap contains only
  whitespace and `//` comments by construction of those grammars) and emitted as COMMENT.
- KStates comments are on the HIDDEN channel and are read from the full token stream.
- `OPERATION`: `SET APPEND SETLIST DROPLIST SETOBJ APPENDOBJ DROPOBJ` (both cases) and `EVENT` when used as an action.
  `KEYWORD`: all other grammar keywords (`type prop has knows list make object STATEMACHINE ATTACHED TO STATES STATE
  INITIAL ENTRY DO TRANSITIONS TRANSITION SPLIT JOIN NOT LOOPING CONDITION VALUE EVAL MACRO MACROS TAKES RETURNS
  DEFINITION reference IF IN SCOPE WITH AS`, plus `EVENT` in conditions).
- **EVAL bodies**: after an `EVAL` keyword and its `{`, the brace-balanced region up to the matching `}` (balance
  counted on the lexer's LBRACE/RBRACE tokens, exactly as the dsl-tools parser does) is embedded Python. Inside it:
  `$( … )` → PATH_REFERENCE, `@name` → MACRO_REFERENCE, Python keywords (`def return for in if elif else while
  import from as and or not None True False lambda pass break continue endif endfor`) → EMBEDDED_KEYWORD, quoted
  strings → EMBEDDED_STRING, numbers → EMBEDDED_NUMBER, `#` to end of line → EMBEDDED_COMMENT, everything else →
  EMBEDDED_CODE. A `//` inside an EVAL body is what the karpfen lexer treats as a comment: emit it as COMMENT and a
  WARNING diagnostic (code `eval-double-slash`): "`//` inside EVAL starts a karpfen comment; the rest of the line is
  dropped. For Python floor division use int(a / b) or math.floor(a / b)."
- Lexer errors become ERROR tokens plus ERROR diagnostics (source LEXER).

### 3.2 Diagnostics

```kotlin
enum class Severity { ERROR, WARNING, INFO, HINT }
enum class DiagnosticSource { LEXER, PARSER, DSL_TOOLS, IDE }
data class Diagnostic(val range: TextRange, val severity: Severity, val message: String, val code: String, val source: DiagnosticSource)
```

### 3.3 Parse outcome (from ANTLR parse trees)

```kotlin
enum class SymbolKind { TYPE, SIMPLE_PROPERTY, HAS_RELATION, KNOWS_RELATION, OBJECT, PROPERTY_ASSIGNMENT, HAS_ASSIGNMENT,
                        KNOWS_ASSIGNMENT, STATEMACHINE, STATE, ENTRY_BLOCK, DO_BLOCK, TRANSITION, SPLIT, JOIN, MACRO,
                        MACRO_PARAMETER, BINDING }
enum class ReferenceKind { TYPE, PRIMITIVE_TYPE, STATE, MACRO, OBJECT, PROPERTY_KEY, RELATION_KEY, MODEL_PATH, EVENT_NAME, EVENT_DOMAIN }
enum class SemanticKind { TYPE_NAME, PRIMITIVE_TYPE, PROPERTY_NAME, OBJECT_NAME, STATE_NAME, MACRO_NAME, EVENT_NAME,
                          EVENT_DOMAIN, MODEL_PATH, DESCRIPTION, BINDING_NAME }
data class SemanticSpan(val range: TextRange, val kind: SemanticKind)
data class OutlineNode(val kind: SymbolKind, val name: String, val detail: String?, val range: TextRange, val selectionRange: TextRange, val children: List<OutlineNode>)
data class SymbolDeclaration(val kind: SymbolKind, val name: String, val nameRange: TextRange, val range: TextRange, val container: String?)
data class SymbolReference(val kind: ReferenceKind, val name: String, val range: TextRange, val qualifier: String?)
enum class FoldingKind { BLOCK, EMBEDDED_CODE, COMMENT }
data class FoldingRange(val range: TextRange, val kind: FoldingKind)
class SyntaxIndex(val declarations: List<SymbolDeclaration>, val references: List<SymbolReference>) { /* lookups by offset/name/kind */ }
sealed interface DslModel { data class Kmeta(val metamodel: meta.Metamodel); data class Kstates(val stateMachine: states.StateMachine) }
data class ParseOutcome(val language: KarpfenLanguage, val version: Long, val syntaxDiagnostics: List<Diagnostic>,
                        val outline: List<OutlineNode>, val index: SyntaxIndex, val folding: List<FoldingRange>,
                        val semanticSpans: List<SemanticSpan>, val standaloneModel: DslModel?, val standaloneDiagnostics: List<Diagnostic>)
interface DocumentParser { fun parse(snapshot: TextSnapshot): ParseOutcome }   // one implementation per language, behind LanguageSupport
```

Rules (MUST):
- Parsing never throws. ANTLR default recovery is used so a broken file still yields a partial tree; the walkers
  must tolerate missing tokens (`<missing …>` tokens with negative indices) and error nodes.
- Ranges of string names point *inside* the quotes (the name), `range` covers the whole construct.
- Declarations/references/semantic spans to extract:
  - KMeta: `type "N" "D"` → TYPE decl (N), D → DESCRIPTION; `prop("k","number")` → SIMPLE_PROPERTY decl + PRIMITIVE_TYPE
    ref; `has/knows("k","T")` and `list("T")` → HAS/KNOWS_RELATION decl + TYPE ref.
  - KModel: `make object "key":"T"` → OBJECT decl (anonymous when key is empty or `_`; name shown as `:T`) + TYPE ref;
    `prop("k")` → PROPERTY_KEY ref (qualifier = enclosing object's type name); `has("k")` / `knows("k")` → RELATION_KEY
    ref (same qualifier); `knows(...) -> "obj"` → OBJECT ref.
  - KStates: `ATTACHED TO "T"` → TYPE ref; states → STATE decls (container = parent state); ENTRY/DO → block outline
    nodes; TRANSITION/SPLIT/JOIN state strings → STATE refs; outline nodes for transitions named `a -> b`, `a -> b, c`,
    `a, b -> c`; `MACRO "m"` → MACRO decl; `TAKES("p","T")` → MACRO_PARAMETER decl + TYPE/PRIMITIVE_TYPE ref;
    `RETURNS(...)`/`list(...)`/`reference(...)` → TYPE refs; `MACRO("m", args…)` call → MACRO ref + MODEL_PATH refs for
    args; `WITH MACRO(...) AS "x"` → BINDING decl; action left sides (`SET("a->b", …)`, `SETOBJ("src","rel", …)`,
    `DROPOBJ("p")` …) → MODEL_PATH refs (relation key in SETOBJ/APPENDOBJ → RELATION_KEY ref); `VALUE("p")` and
    `IF IN SCOPE("p", …)` → MODEL_PATH refs; `EVENT("d","n")` (condition and action) → EVENT_DOMAIN + EVENT_NAME refs;
    `$(path)` inside EVAL → MODEL_PATH refs; `@m(` inside EVAL → MACRO refs.
- Folding: every brace block spanning more than one line (BLOCK; EVAL bodies as EMBEDDED_CODE) and runs of ≥ 2
  consecutive comment lines (COMMENT).
- Standalone semantics: when there are no syntax diagnostics, KMeta is converted with `KmetaDSLConverter.parseKmetaString`
  and KStates with `KstatesDSLConverter.parseKstatesString`; exceptions become DSL_TOOLS diagnostics whose range is
  located best-effort (search the reference/declaration whose name appears quoted in the exception message; otherwise
  the first token of the file). KModel needs a metamodel and is converted in core-workspace.

### 3.4 Document session

```kotlin
data class DocumentId(val path: java.nio.file.Path)     // absolute, normalized
class DocumentSession(val id: DocumentId, val support: LanguageSupport, val text: EditableText, scope: CoroutineScope, config: DocumentSessionConfig = DocumentSessionConfig()) : AutoCloseable {
    val tokens: StateFlow<TokenizeResult>          // recomputed for every version, conflated (mapLatest), no debounce
    val parse: StateFlow<ParseOutcome?>            // debounced (default 120 ms), cancellable, latest wins
    val lastGoodParse: StateFlow<ParseOutcome?>    // latest outcome without syntax diagnostics
    val diagnostics: StateFlow<VersionedDiagnostics>  // lexer + parser + standalone + external, merged, sorted
    fun setExternalDiagnostics(source: String, version: Long, diagnostics: List<Diagnostic>)
}
```

Budgets (targets; documented by `budget`-tagged tests that no test run executes, see §1): tokenize 5,000-line
KStates < 30 ms, parse < 150 ms.

## 4. core-workspace (`io.karpfen.ide.core.workspace`)

- `Workspace(root, scope, options)`: discovers files recursively, ignoring `.git .gradle build out .idea .kotlin node_modules`
  and hidden directories; exposes `files: StateFlow<WorkspaceTree>` (directories first, then files, case-insensitive name
  order). `FileKind`: KMETA, EVENTS_KMETA (file name `EVENTS.kmeta`, case-insensitive), KMODEL, KSTATES, ENV_DESCRIPTOR
  (`*.kenv.json`), LAYOUT (`*.layout.json`), OTHER. File watching via `java.nio.file.WatchService` with recursive
  registration plus explicit `refresh()`; changes are debounced (200 ms).
- `DocumentManager`: `open(path)`, `get`, `close`, `save`, `saveAll`, `openDocuments: StateFlow<List<OpenDocument>>`.
  `OpenDocument` has `session: DocumentSession`, `isDirty: StateFlow<Boolean>` (from `UndoManager.isAtSavePoint`),
  `lineSeparator`, `conflict: StateFlow<ExternalChange?>`. Saving writes atomically (temp file in the same directory +
  atomic move), restores the line separator, marks the save point, and ignores the self-induced watch event.
  External change: not dirty → reload as one undoable `External` edit; dirty → set `conflict` (UI offers Reload/Keep mine).
- Environment descriptor `*.kenv.json` (kotlinx.serialization), exactly the shape in the proposal §5.2:
  `metamodel, events?, model, stateMachines[{file, attachedTo}], tickDelayMs?, eventTtlMs?, observe?{objects, domains},
  runtime?{image?, pythonPackages?}, bridges[]`. Unknown keys are a WARNING, not an error. Parse errors and missing
  referenced files are diagnostics on the descriptor (best-effort range: the JSON string value naming the file).
- Modeling contexts: each descriptor defines one context (paths relative to its directory). Every directory with karpfen
  files not covered by a descriptor forms an implicit context: metamodel = the single `.kmeta` that is not `EVENTS.kmeta`
  (several → INFO diagnostic on each, and the alphabetically first is used), events = `EVENTS.kmeta`, models = all
  `.kmodel`, state machines = all `.kstates`.
- `ProjectAnalyzer` (in `Workspace.analysis: StateFlow<ProjectAnalysis>`): for each context, using the editor text of
  open documents and disk text otherwise: metamodel via `KmetaDSLConverter.parseKmetaString`; events via
  `parseKmetaString(text, baseTypes = metamodel.types)`; each model via `KmodelDSLConverter.parseKmodelString(text,
  metamodel)`; state machines via `KstatesDSLConverter.parseKstatesString`. Exceptions → DSL_TOOLS diagnostics on the
  right file (best-effort ranges as in §3.3). Re-analysis is debounced (300 ms), cancellable, and pushes per-file
  diagnostics into open sessions via `setExternalDiagnostics("project", …)`. No IDE-side validation rules beyond
  what dsl-tools reports.

## 5. core-lang (`io.karpfen.ide.core.lang`)

```kotlin
enum class HighlightStyle { KEYWORD, OPERATION, STRING, NUMBER, COMMENT, PUNCTUATION, OPERATOR, IDENTIFIER,
    TYPE_NAME, PRIMITIVE_TYPE, PROPERTY_NAME, OBJECT_NAME, STATE_NAME, MACRO_NAME, EVENT_NAME, EVENT_DOMAIN, MODEL_PATH,
    DESCRIPTION, BINDING_NAME, EMBEDDED_CODE, EMBEDDED_KEYWORD, EMBEDDED_COMMENT, EMBEDDED_STRING, EMBEDDED_NUMBER,
    PATH_REFERENCE, MACRO_REFERENCE, ERROR }
data class HighlightSpan(val range: TextRange, val style: HighlightStyle)
data class LanguageDocument(val path: Path, val language: KarpfenLanguage, val text: TextSnapshot, val tokens: TokenizeResult, val parse: ParseOutcome?)
data class Location(val path: Path, val range: TextRange)
data class CompletionItem(val label: String, val kind: CompletionKind, val insertText: String, val replaceRange: TextRange,
                          val caretOffsetInInsert: Int?, val detail: String?, val documentation: String?, val sortKey: String)
data class Hover(val range: TextRange, val text: String)
interface LanguageServices {                                          // facade; DefaultLanguageServices delegates to per-language providers
    fun highlight(doc: LanguageDocument): List<HighlightSpan>       // semantic spans overlay lexical ones when versions match; sorted, non-overlapping
    fun complete(doc: LanguageDocument, offset: Int): List<CompletionItem>
    fun definition(doc: LanguageDocument, offset: Int): List<Location>
    fun references(doc: LanguageDocument, offset: Int): List<Location>
    fun hover(doc: LanguageDocument, offset: Int): Hover?
    fun matchingBracket(doc: LanguageDocument, offset: Int): Pair<TextRange, TextRange>?
}
```

Completion (MUST, context determined from tokens + partial parse tree around the caret, robust on broken text):
- KMeta: top level → `type "Name" "Description" { }` snippet; in a type body → `prop("…", "…")`, `has("…", "…")`,
  `knows("…", "…")`, `list("…")`; the second argument of `prop(` → `number string boolean`; the type argument of
  `has(`/`knows(`/`list(` → types declared in the file plus the context's domain types when editing `EVENTS.kmeta`.
- KModel: object body → `prop("…") -> "…"`, `has("…") -> make object "…":"…" { }`, `knows("…") -> "…"`; inside `prop("`
  → simple properties of the enclosing object's type; inside `has("` / `knows("` → relation keys of that type
  (only `has` relations for `has`, only `knows` for `knows`); `make object "k":"` → types (for a `has` value: the
  relation's target type first); `knows("r") -> "` → keys of objects in the same model whose type matches.
- KStates: structural keywords by position (`STATE`, `INITIAL STATE`, `ENTRY`, `DO`, `TRANSITION`, `SPLIT`, `JOIN`,
  `NOT LOOPING`, `CONDITION`, `EVENT`, `EVAL`, `VALUE`, `MACRO`, `TAKES`, `RETURNS`, `DEFINITION`, action operations,
  `IF IN SCOPE`, `WITH … AS`); state names inside transition strings; macro names inside `MACRO("`; `ATTACHED TO "` →
  domain types; model paths inside action/`VALUE`/`IF IN SCOPE`/macro-argument strings and after `$(` in EVAL, resolved
  segment by segment over props/has/knows of the attached type (`->` separated; `event->` resolves against the events
  metamodel type of the transition's EVENT clause when determinable, else all event types' fields; `WITH` bindings and
  macro parameters resolve by their declared type); event names in the second argument of `EVENT(` from `EVENTS.kmeta`.
- Structure: `DefaultLanguageServices` delegates to per-language strategy objects (`HighlightProvider`,
  `CompletionProvider`, `NavigationProvider`, `HoverProvider`) supplied by a `LanguageFeatures` factory per language;
  shared mechanics (context detection around the caret, model-path resolution over a metamodel) are separate,
  reusable collaborators, not copied per language.
- Prefix filtering is NOT done here (the editor filters); items carry `replaceRange` covering the identifier/string
  content under the caret.

Definition/references: state refs ↔ decls (file), macro refs ↔ decls (file), type refs → TYPE decl in the context's
`.kmeta`/`EVENTS.kmeta` (cross-file), KModel object refs → object decls (file), `ATTACHED TO` → type, property/relation
keys → declaration in the metamodel. Hover: type → its description and members; property/relation → `prop x: number`,
`has position: Vector`, `knows obstacles: list(Obstacle)`; state → its hierarchy path; macro → `TAKES`/`RETURNS`
signature; model path → resolved type of the path.

## 6. ui-foundation (`io.karpfen.ide.ui.foundation`)

- `KarpfenTheme(darkTheme, content)` with `KarpfenColors` (window, panel, border, text, textMuted, accent, selection,
  focus, error, warning, info) provided through a CompositionLocal; monospace and UI typography; light and dark palettes.
- Widgets: `SplitPane` (horizontal/vertical, draggable divider, min sizes, fraction state hoisted), `TabStrip` (title,
  tooltip, dirty dot, close button, middle-click close, horizontal overflow scroll), `TreeView<T>` (lazy, expand/collapse,
  single selection, keyboard Up/Down/Left/Right/Enter, double-click open, icon and context-menu slots), `ToolWindow`
  (header + content), `StatusBar`, small vector `KarpfenIcons` (folder, file, kmeta, kmodel, kstates, json, error,
  warning, info, close, chevron right/down, search). No material-icons-extended.
- `CommandRegistry`: `Command(id, title, shortcut, isEnabled, action)`, platform-aware `KeyShortcut` (Cmd on macOS,
  Ctrl elsewhere), lookup by `KeyEvent`, and data to build the window `MenuBar`.

## 7. ui-editor (`io.karpfen.ide.ui.editor`, Compose-free logic in `io.karpfen.ide.ui.editor.model`)

### 7.1 Model (no Compose imports; unit-tested)
- `CaretModel` over an `EditableText`: one caret with anchor/position/preferred column (list-ready for multi-caret);
  movement by char, word (identifier chars `[A-Za-z0-9_]`; punctuation runs; whitespace), line, document, page;
  smart Home (first non-whitespace, then column 0); selection extension; select word/line/all; carets are mapped
  through every text change.
- `EditorAction` (Command pattern) implementations, one class per cohesive group, registered in an
  `EditorActionRegistry`; a platform-aware `EditorKeymap` maps shortcuts to action ids. Actions: type text (replaces selection; auto-indent on Enter = indentation of the current line, +4 spaces
  after a line ending in `{`; Enter between `{` and `}` puts `}` on its own line), auto-close `"`, `(`, `{` when the next
  char is whitespace/EOL/closing bracket and over-type an auto-inserted closer, Backspace (selection, char, smart
  unindent to the previous 4-column stop inside leading whitespace, deletes an auto-inserted pair), Delete,
  delete word left/right, delete line, duplicate line/selection, move line up/down, indent/outdent (Tab/Shift+Tab,
  4 spaces), toggle line comment (`//`; inside an EVAL body `#`, because `//` there is a karpfen comment), copy/cut
  (whole line when nothing is selected), paste (separators normalized to `\n`), undo/redo restoring carets.
  Every action is exactly one undo step.
- `FindModel`: query, case-sensitive, whole-word, regex (invalid regex → error state, no crash), matches for the
  current snapshot, current match, next/previous (wrapping), replace / replace all (one undo step).
- `CompletionSessionModel`: open on explicit request or auto-trigger (identifier char typed after ≥ 1 identifier char,
  `"` typed in a completable string position, `$(` or `->` typed), filter items by the text between
  `replaceRange.start` and the caret (case-insensitive prefix > camel/word-boundary > subsequence), stable sort
  (match quality, then sortKey, then label), accept inserts `insertText` over `replaceRange` as one undo step and puts the
  caret at `caretOffsetInInsert`, closes when the caret leaves the range, on Escape, or on focus loss.

### 7.2 View
- `CodeEditor(state: CodeEditorState, modifier, onNavigate: (Location) -> Unit)`; `CodeEditorState` bundles the
  `OpenDocument`, `LanguageServices`, models, scroll state and `EditorSettings` (font size, tab display width 4, line
  numbers on). `rememberCodeEditorState(...)`.
- Rendering: virtualized (only visible lines + overscan are laid out, using `TextMeasurer`/`drawText` with a per-line
  `TextLayoutResult` LRU cache keyed by line text + spans + font settings); gutter with one-based line numbers and
  diagnostic markers; current-line highlight; selection; blinking caret (solid while typing); wavy underlines (ERROR,
  WARNING) and dotted (INFO); matching-bracket boxes; find-match highlights; syntax colours from `HighlightStyle` via an
  `EditorTheme` (light/dark); tab characters displayed at 4-column stops with correct caret mapping; horizontal and
  vertical scrolling with Compose desktop scrollbars driven by custom adapters. Highlight spans computed for an older
  version are mapped through `EditableText.mapOffset` until fresh ones arrive.
- Input: focusable; navigation and command keys via a platform-aware keymap; **typed text must work for every keyboard
  layout, including macOS Option-combinations (German layout: Option+8 = `{`, Option+L = `@`), dead keys and IME**.
  Use Compose's platform text-input API (`PlatformTextInputModifierNode` / `establishTextInputSession`) if it is usable
  on desktop in this Compose version; otherwise a focused, visually hidden proxy `BasicTextField` positioned at the caret
  whose committed text is forwarded as typing. Document the chosen approach in KDoc. Expose
  `SemanticsActions.InsertTextAtCursor` so `performTextInput` works in tests. Clipboard via the non-deprecated Compose
  clipboard API or AWT.
- Mouse: click places caret; drag selects (auto-scroll at edges); double-click word; triple-click line; Shift+click
  extends; Cmd/Ctrl+click → `onNavigate(definition)`; hover (600 ms) shows diagnostics at the position, otherwise the
  language hover; context menu (Cut, Copy, Paste, Go to Definition, Toggle Comment).
- Popups (separate files, hosted by `CodeEditor`): completion list (anchored at the caret, ≤ 12 rows, kind badge, label,
  detail; keyboard handled while open), find/replace bar (Cmd/Ctrl+F, Cmd/Ctrl+R for replace; toggles Aa / W / .*; "n/m"
  counter; Enter/Shift+Enter; Escape returns focus), go-to-line (Cmd+L on macOS, Ctrl+G elsewhere; accepts `line[:col]`),
  hover tooltip.
- `scrollTo(offset, center)` and caret auto-scroll keep the caret visible with a 3-line margin.
- Performance: a headless benchmark renders a 10,000-line KStates document scrolled to the middle and reports average
  frame time after warm-up (target < 50 ms; a `budget`-tagged test, not run, see §1).

## 8. ui-workbench (`io.karpfen.ide.ui.workbench`)

- `WorkbenchState` is a coordinator (Mediator) over cohesive sub-states, each a plain Kotlin class with its own tests:
  `ProjectTreeState`, `EditorTabsState`, `OutlineState`, `ProblemsState`, `StatusBarState`, `DialogState`. It offers
  `openFolder(path)`, current `Workspace`, open editor tabs (order, active tab), tool-window
  visibility and sizes, dialogs (unsaved changes on tab close and on exit; external-change conflict banner), `navigateTo(Location)`.
- `Workbench(state)`: left Project tree (file-kind icons; double-click/Enter opens; context menu New KMeta/KModel/KStates/
  Environment file from templates, Rename, Delete (to trash when supported, with confirmation), Reveal in Finder/
  Explorer); centre editor tabs + `CodeEditor`; right Outline of the active document (click navigates; node containing
  the caret highlighted); bottom Problems (grouped by file, severity icon, message, `line:col`, error/warning filter,
  double-click navigates); status bar (`Ln x, Col y`, selection length, language, line separator, `UTF-8`, error and warning
  counts, "Analyzing…" indicator); empty state with "Open Folder…" and recent folders.
- `SettingsRepository` (Repository pattern) persists `IdeSettings` (recent folders ≤ 10, theme, editor font size, window bounds, tool-window sizes) persisted as JSON in
  `~/.karpfen-ide/settings.json`; corrupt files are backed up and reset. The settings directory is injectable for tests.

## 9. app/ide and tools/cli

- `app/ide`: `main(args)` with optional folder argument; native macOS menu bar; `MenuBar` built from the
  `CommandRegistry` (File: Open Folder…, Open Recent, Save, Save All, Close Tab; Edit: Undo, Redo, Cut, Copy, Paste,
  Select All, Find, Replace, Toggle Comment, Duplicate Line; View: Project, Outline, Problems, Light/Dark theme;
  Navigate: Go to Line, Go to Definition); window title `<folder> — karpfen IDE`; window bounds persisted; close request
  runs the unsaved-changes flow; uncaught exceptions are logged and shown in a dialog instead of killing the app;
  `logback.xml` (console WARN; rolling file `~/.karpfen-ide/logs/ide.log`); `KARPFEN_IDE_EXIT_AFTER_MS` kept.
- `tools/cli`: `karpfen validate <dir> [--format text|json]` (start script `karpfen`) loads a `Workspace` without
  watching, runs the project analysis plus per-file syntax parsing, prints `path:line:col: severity: message [code]`
  (one-based), exits 1 if any ERROR. dsl-tools prints to stdout while parsing; the CLI must keep its own output clean
  (redirect `System.out` during analysis and print through the original stream).

## 10. Test fixtures

`core/core-document` applies `java-test-fixtures`. `src/testFixtures/resources/examples/` holds copies of every
`.kmeta/.kmodel/.kstates` file from karpfen-runtime `examples/` (commit d4c2059: single_robot_example,
delivery_drone_example, autonomous_car_example, drone_swarm_example) and karpfen-dsl-tools `example/` (commit 859a95d:
metamodel_dsl_example.kmeta, model_dsl_example.kmodel, statemachine_full_example/*), each in a folder named after its
origin, plus `NOTICE.md` stating origin, commits and the Apache-2.0 license. Kotlin helpers in `src/testFixtures/kotlin`:
`Examples.files()`, `Examples.contexts()`, `Mutations.random(text, seed, count)` (deletions, insertions of grammar
punctuation/keywords, truncations), and a generator for a large synthetic KStates document (N states, transitions, EVAL bodies).

## 11. Definition of done for P1

1. `./gradlew build` green in artifact mode and composite mode, including architecture tests.
2. Every example file tokenizes and parses with zero LEXER/PARSER diagnostics; every example context analyzes with zero
   DSL_TOOLS errors.
3. Robustness: tokenizer, parser, outline, index, highlight, completion, definition, hover never throw on 10,000
   deterministic random mutations of the examples (and on every prefix of one KStates example).
4. Editor model and Compose UI tests listed in §7 pass headless. (The benchmark exists as a `budget`-tagged test and
   is not run since ADR 0011.)
5. `karpfen validate <examples dir>` exits 0 and prints no errors.
6. `KARPFEN_IDE_EXIT_AFTER_MS=6000 ./gradlew :app:ide:run --args=<examples dir>` exits 0 with no ERROR in the log.
7. `docs/p1-manual-test.md` lists a 10-minute manual smoke test for the owner (typing on a German macOS layout,
   completion, navigation, save/undo, external change).
