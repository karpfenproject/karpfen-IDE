# 3. karpfen-dsl-tools is the language core; the IDE adds only editor concerns

- Status: Accepted (decided by the owner); CI part temporarily amended by [ADR 0010](0010-dsl-tools-resolution-modes.md)
- Date: 2026-10-20
- Source: architecture proposal D2, §5.1

## Context

The karpfen languages are defined by ANTLR grammars in karpfen-dsl-tools, which the runtime also uses. The grammar
is explicitly experimental and evolving. A second parser inside the IDE would drift from the one the runtime uses.

## Decision

The IDE depends on karpfen-dsl-tools for lexing, parsing, the semantic model and, as it grows there, semantic
analysis. There is no second parser in the IDE. Language intelligence (validators, scope/query API) is added to
dsl-tools; the IDE adds editor concerns only (text buffer, parse scheduling, snapshots, text patching, UI).
To serve as an editor backend, dsl-tools gets the additions listed in §5.1 (source ranges via a `SourceMap`,
diagnostics, tolerant parsing, tokenizer, printers, scope/query API, housekeeping and published artifacts).
Locally the dependency is a Gradle composite build; CI uses published artifacts.

## Consequences

- Runtime and editor agree on the languages by construction.
- Until §5.1 lands, the IDE uses the ANTLR lexer for highlighting and the strict parser for file diagnostics.
- Cross-repository changes are common; the build supports a composite build of dsl-tools (ADR 0010).
