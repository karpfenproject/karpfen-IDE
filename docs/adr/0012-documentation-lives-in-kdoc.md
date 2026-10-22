# 12. Documentation lives in KDoc; markdown documents are maps

- Status: Accepted; partially supersedes the consequences of [Record architecture decisions](0001-record-architecture-decisions.md)
- Date: 2026-10-22
- Source: the owner's decision after phase 1 on the documentation style, which the
  [plan of the increments after phase 1](../plans/increments-after-phase-1.md) makes part of the first increment

## Context

Phase 1 documented the code with line comments between statements, and the comments, build scripts and markdown
documents referred to each other by section numbers, work-package keys and decision numbers. A reader had to look up
an id to understand a sentence, and the same technical detail was kept in several places that drifted apart. The record
on how decisions are kept even asked code comments to link to the decision records instead of explaining themselves.

## Decision

- Documentation lives in KDoc block comments on declarations, in official KDoc syntax. There are no line comments, no
  other block comments and no comments between statements or after code; code that needs an explanation gets a
  well-named function or value with KDoc, or the explanation goes into the KDoc of the enclosing declaration. A
  Gradle script has no other place to document its top-level statements and blocks, such as `dependencies { }`, so
  there KDoc may document those too.
- Technical detail, including the reasons for a design, lives in the KDoc next to the code. The markdown documents
  (the README, the codebase guide, the manual test and the plans) are maps: they give the big picture and say where to
  read in the code, without repeating it.
- Documents are written for a human reader and name other documents, sections and decisions in words, never by an id.
  The decision records keep their numbers as file names and in their index, but no text refers to a record by number.

## Consequences

- The consequence of the first record that build scripts, tests and code comments link to the decision records
  instead of repeating the rationale no longer holds: the code explains its own design in KDoc.
- `DocumentationStyleTest` in architecture-tests enforces the rules on every build: no comments other than KDoc, KDoc
  only where it documents a declaration or a top-level statement of a script, and no reference ids in comments,
  markdown documents, CI workflows and XML resources. The archived records of phase 1 below `docs/history` keep their
  ids.
- The [codebase guide](../codebase-guide.md) states the conventions and is the map of the code.
