# 10. karpfen-dsl-tools resolution: composite build or artifact

- Status: Accepted; partially supersedes the CI part of
  [the decision that dsl-tools is the language core](0003-dsl-tools-is-the-language-core.md) until dsl-tools is
  published
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

The KDoc of the resolution block in `settings.gradle.kts` documents how the checkout is found, what happens when a
configured path holds no build and how to see the active mode; the KDoc at the end of
`core/core-document/build.gradle.kts` documents how the composite build meets the configuration cache; the CI workflow
`.github/workflows/build.yml` holds the pinned commit.

## Consequences

- Cross-repository changes are a single local iteration.
- CI builds against a known dsl-tools commit, which is raised on purpose when the IDE needs a newer one.
- Composite builds give up the configuration cache until dsl-tools generates its ANTLR sources at build time, part of
  the housekeeping the proposal asks of it under
  [what dsl-tools must provide](../architecture-proposal.md#language-core-what-dsl-tools-must-provide-what-the-ide-adds).
