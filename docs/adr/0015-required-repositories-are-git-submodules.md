# 15. Required repositories are git submodules of this repository

- Status: Accepted; partially supersedes [karpfen-dsl-tools resolution](0010-dsl-tools-resolution-modes.md) (where the
  checkout for the composite build lives, and how CI gets it)
- Date: 2026-10-26
- Source: owner rule: every repository the IDE needs is hooked into this repository as a git submodule, and work happens
  only inside this workspace

## Context

The composite build used to look for a karpfen-dsl-tools checkout next to this repository, outside the workspace.
Work on dsl-tools was done in such a sibling checkout and was lost when that folder was removed, because nothing in
this repository referred to it.

## Decision

- karpfen-dsl-tools is a git submodule of this repository in the directory `karpfen-dsl-tools`, and that checkout is the
  default for the composite build. Other repositories the IDE needs later, such as karpfen-runtime, join the same way.
- Work on a required repository happens in its submodule, on a branch of that repository; this repository records the
  submodule commit it builds against.
- CI checks the submodules out together with this repository instead of cloning them separately.
- Nothing is written outside the workspace: no sibling checkouts, and no publishing into the local Maven repository.
  Artifact mode stays as a fallback for an uninitialized submodule and for a published artifact later.

## Consequences

- A fresh clone needs `git submodule update --init`; the settings warn and fall back to the artifact when it is missing.
- A submodule commit that is not pushed to its own repository cannot be fetched by CI or by other clones, so submodule
  branches are pushed before this repository's commits that point to them.
- The architecture tests and the documentation rules read `.gitmodules` and leave submodule checkouts out, since those
  follow their own repository's rules; `Submodules` in the architecture tests holds that logic.
