# Architecture Decision Records

Decisions for karpfen IDE, recorded as described in [Record architecture decisions](0001-record-architecture-decisions.md).
The detailed design is in the [architecture proposal](../architecture-proposal.md) (v0.3, accepted 2026-10-20); the
current map of the code is the [codebase guide](../codebase-guide.md).

| ADR | Title | Origin | Status |
|---|---|---|---|
| 0001 | [Record architecture decisions](0001-record-architecture-decisions.md) | how decisions are kept | Accepted; the consequence on code comments is superseded by [the documentation style](0012-documentation-lives-in-kdoc.md) |
| 0002 | [Text files are the single source of truth; diagrams are projections with a layout sidecar](0002-text-files-are-the-single-source-of-truth.md) | key decision of the proposal | Accepted |
| 0003 | [karpfen-dsl-tools is the language core; the IDE adds only editor concerns](0003-dsl-tools-is-the-language-core.md) | key decision of the proposal | Accepted; the CI part is partially superseded by [dsl-tools resolution](0010-dsl-tools-resolution-modes.md) |
| 0004 | [Hybrid notation: graph structure is graphical, leaves are textual](0004-hybrid-notation.md) | key decision of the proposal | Accepted |
| 0005 | [UI toolkit: Compose Multiplatform for Desktop with a custom editor and diagram canvas](0005-compose-multiplatform-for-desktop.md) | key decision of the proposal | Accepted |
| 0006 | [The runtime runs in an IDE-owned Docker container by default; wire protocol only](0006-runtime-in-ide-owned-docker-container.md) | key decision of the proposal | Accepted; what it says about the client's libraries is superseded by [the runtime client with IDE support](0016-the-runtime-client-talks-to-the-runtime-with-ide-support.md) |
| 0007 | [Bridges are headless-first](0007-headless-first-bridges.md) | key decision of the proposal | Accepted |
| 0008 | [Module boundaries are enforced by the build](0008-module-boundaries-enforced-by-the-build.md) | key decision of the proposal | Accepted |
| 0009 | [Reactive backbone: kotlinx.coroutines with Flow and StateFlow](0009-coroutines-flow-reactive-backbone.md) | key decision of the proposal | Accepted |
| 0010 | [karpfen-dsl-tools resolution: composite build or artifact](0010-dsl-tools-resolution-modes.md) | language core design of the proposal | Accepted; partially supersedes the CI part of [the language core decision](0003-dsl-tools-is-the-language-core.md); the checkout location, the CI part and the artifact source are superseded by [required repositories are git submodules](0015-required-repositories-are-git-submodules.md) |
| 0011 | [Time-budget tests are written but not run](0011-time-budget-tests-are-not-run.md) | owner decision after phase 1 | Accepted |
| 0012 | [Documentation lives in KDoc; markdown documents are maps](0012-documentation-lives-in-kdoc.md) | owner decision after phase 1 | Accepted; partially supersedes [Record architecture decisions](0001-record-architecture-decisions.md) |
| 0013 | [Environment descriptors are edited in the IDE with an IDE-owned JSON parser](0013-environment-descriptors-edited-in-the-ide.md) | owner decision after phase 1 | Accepted |
| 0014 | [dsl-tools output stays off the primary console](0014-dsl-tools-output-stays-off-the-primary-console.md) | owner decision after phase 1 | Accepted; built in the second increment |
| 0015 | [Required repositories are git submodules of this repository](0015-required-repositories-are-git-submodules.md) | owner rule | Accepted; partially supersedes [dsl-tools resolution](0010-dsl-tools-resolution-modes.md) |
| 0016 | [The runtime client talks to the runtime with IDE support](0016-the-runtime-client-talks-to-the-runtime-with-ide-support.md) | owner decisions on the runtime client | Accepted; built in the third increment; partially supersedes [the runtime decision](0006-runtime-in-ide-owned-docker-container.md) |
| 0017 | [Deployment uploads the saved files and the descriptor holds the deploy configuration](0017-deployment-uploads-the-saved-files.md) | owner decisions on deploying | Accepted; built in the third increment |
| 0018 | [Runtime work is tested against an anchored fake and a throwaway runtime container](0018-runtime-work-is-tested-against-an-anchored-fake-and-a-throwaway-runtime-container.md) | owner decisions on tests against a real runtime | Accepted; built in the third increment |

New ADRs take the next free number. A decision is changed by a new ADR that supersedes the old one in whole or in
part; the old ADR's status line and this index then point to the new one.
