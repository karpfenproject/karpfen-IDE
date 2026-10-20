# Architecture Decision Records

Decisions for karpfen IDE, recorded as described in [ADR 0001](0001-record-architecture-decisions.md).
The detailed design is in the [architecture proposal](../architecture-proposal.md) (v0.3, accepted 2026-10-20).

| ADR | Title | Proposal | Status |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | — | Accepted |
| [0002](0002-text-files-are-the-single-source-of-truth.md) | Text files are the single source of truth; diagrams are projections with a layout sidecar | D1 | Accepted |
| [0003](0003-dsl-tools-is-the-language-core.md) | karpfen-dsl-tools is the language core; the IDE adds only editor concerns | D2 | Accepted; CI part partially superseded by 0010 |
| [0004](0004-hybrid-notation.md) | Hybrid notation: graph structure is graphical, leaves are textual | D3 | Accepted |
| [0005](0005-compose-multiplatform-for-desktop.md) | UI toolkit: Compose Multiplatform for Desktop with a custom editor and diagram canvas | D4 | Accepted |
| [0006](0006-runtime-in-ide-owned-docker-container.md) | The runtime runs in an IDE-owned Docker container by default; wire protocol only | D5 | Accepted |
| [0007](0007-headless-first-bridges.md) | Bridges are headless-first | D6 | Accepted |
| [0008](0008-module-boundaries-enforced-by-the-build.md) | Module boundaries are enforced by the build | D7 | Accepted |
| [0009](0009-coroutines-flow-reactive-backbone.md) | Reactive backbone: kotlinx.coroutines with Flow and StateFlow | D8 | Accepted |
| [0010](0010-dsl-tools-resolution-modes.md) | karpfen-dsl-tools resolution: composite build or artifact | §5.1 | Accepted; partially supersedes 0003 (CI part) |

New ADRs take the next free number. A decision is changed by a new ADR that supersedes the old one in whole or in
part; the old ADR's status line and this index then point to the new one.
