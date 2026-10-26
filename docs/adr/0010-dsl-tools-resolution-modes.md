# 10. karpfen-dsl-tools resolution: composite build or artifact

- Status: Accepted; partially supersedes the CI part of
  [the decision that dsl-tools is the language core](0003-dsl-tools-is-the-language-core.md) until dsl-tools is
  published. Where the checkout lives and how CI gets it are superseded by
  [required repositories are git submodules](0015-required-repositories-are-git-submodules.md)
- Date: 2026-10-20
- Source: the architecture proposal's [language core decision](../architecture-proposal.md#karpfen-dsl-tools-is-the-language-core-the-ide-adds-only-editor-concerns), the dependency mechanics under
  [what dsl-tools must provide](../architecture-proposal.md#language-core-what-dsl-tools-must-provide-what-the-ide-adds), the advice to pin dsl-tools in its
  [risks and mitigations](../architecture-proposal.md#risks-and-mitigations) and the accepted recommendation on the composite build path
  ([decisions record](../architecture-proposal.md#decisions-record))

## Context

The IDE depends on dsl-tools, its [language core](0003-dsl-tools-is-the-language-core.md); both repositories often
change together. The language core decision and its dependency mechanics have CI use published artifacts, but
dsl-tools is not published to a remote repository yet.

## Decision

- **Composite build**: a local dsl-tools checkout, configured per machine or found next to this repository, is
  included in the build and replaces the artifact.
- **Artifact**: without a checkout, the version of the version catalog is resolved from the local Maven repository,
  and from a published repository once dsl-tools has one.
- **CI, a temporary deviation from the language core decision**: CI clones dsl-tools at a pinned commit, as the
  proposal's risk list advises, and builds in composite mode until dsl-tools publishes versioned artifacts.

The KDoc of the resolution block in `settings.gradle.kts` documents how the checkout is found, which version of
dsl-tools it must hold, what happens when a configured path holds no build and how to see the active mode. Since the
[decision that required repositories are git submodules](0015-required-repositories-are-git-submodules.md), the
submodule commit this repository records is the pinned commit, and the CI workflow checks it out with the repository.

## Consequences

- Cross-repository changes are a single local iteration.
- CI builds against a known dsl-tools commit, which is raised on purpose when the IDE needs a newer one.
- Composite builds gave up the configuration cache as long as dsl-tools rewrote its committed ANTLR sources, a
  housekeeping task the proposal asks of dsl-tools under
  [what dsl-tools must provide](../architecture-proposal.md#language-core-what-dsl-tools-must-provide-what-the-ide-adds).
  dsl-tools 2.0 generates its parsers at build time, so both modes now keep the configuration cache.
- dsl-tools 2.0 also moved its packages below `io.karpfen.dsl`, and the IDE builds against that version, so a checkout
  used for the composite build must hold dsl-tools 2.0 or newer; an older one, such as a submodule that still pins
  version 1.0, no longer compiles with the IDE, and the settings stop the build with a message that says so.
