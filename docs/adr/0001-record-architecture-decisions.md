# 1. Record architecture decisions

- Status: Accepted; the consequence on code comments is superseded by
  [Documentation lives in KDoc; markdown documents are maps](0012-documentation-lives-in-kdoc.md)
- Date: 2026-10-20

## Context

The architecture of karpfen IDE was worked out in [the architecture proposal](../architecture-proposal.md)
(v0.3, accepted by the owner on 2026-10-20). The proposal is long and mixes decisions with design detail and
research. Decisions need a stable, individually linkable record that code, build scripts and reviews can refer to.

## Decision

We record architecturally significant decisions as lightweight ADRs in `docs/adr/`, numbered sequentially
(`NNNN-short-title.md`), each with Status, Context, Decision and Consequences. The second to the ninth record
capture the eight key decisions of the proposal; later decisions get new numbers. An accepted ADR is not edited in
substance; it is superseded, in whole or in part, by a new ADR that links back to it, and only its status line is
updated to point to the new ADR. The proposal remains the detailed design reference.

## Consequences

- The index in [README.md](README.md) lists every ADR with its status.
- Build scripts, tests and code comments link to ADRs instead of repeating rationale.
- Changing a decision requires a new ADR, which makes the change visible in review.
