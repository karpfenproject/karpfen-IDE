# 2. Text files are the single source of truth; diagrams are projections with a layout sidecar

- Status: Accepted
- Date: 2026-10-20
- Source: architecture proposal D1, §5.4, §5.5, §9 item 1

## Context

The karpfen runtime consumes `.kmeta`, `.kmodel` and `.kstates` text. Textual editing is a first-class
requirement, git diffs must stay meaningful, and inventing a separate model persistence format would create a
second source of truth that can diverge from what the runtime sees.

## Decision

The text files are the only persisted model. Diagrams are projections of the parsed text. Graphical edits are
compiled into minimal text edits so that comments, formatting and embedded Python bodies survive. Layout lives
in an optional, regenerable sidecar `<file>.layout.json` next to the source, keyed by stable element
identities, and is committed with the source.

## Consequences

- There is a single writer: the diagram is always refreshed from the text, so text and diagram cannot diverge.
- A text patcher (semantic command → `TextEdit[]`) and source ranges from dsl-tools are required.
- Pure geometry changes (drag, resize, waypoints) only touch the layout sidecar.
- Parsed models are treated as immutable by the IDE; every change is a text edit.
