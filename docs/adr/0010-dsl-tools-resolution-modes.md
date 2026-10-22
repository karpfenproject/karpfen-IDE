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

- **Composite build**: `dslToolsPath` from `-PdslToolsPath`, else `local.properties` (git-ignored), else
  `../karpfen-dsl-tools`; a Gradle build there is included and substitutes the module (`using(project(":"))`).
- **Artifact**: otherwise (or an empty `dslToolsPath`): the catalog version from `mavenLocal()` (`io.karpfen` only).
- **CI, a temporary deviation from the language core decision**: CI clones dsl-tools at a commit pinned in
  `DSL_TOOLS_REF`, as the proposal's risk list advises, to the default path and builds in composite mode, until
  dsl-tools publishes versioned artifacts.

## Consequences

- Cross-repository changes are a single local iteration. The settings log the active mode, though not on a
  configuration-cache hit (`--no-configuration-cache` or `dependencyInsight` then shows it).
- dsl-tools is not configuration-cache compatible yet: composite builds store no cache entry, and dsl-tools tasks
  invoked directly need `--no-configuration-cache` (until dsl-tools generates its ANTLR sources at build time, part
  of the housekeeping the proposal asks of it under [what dsl-tools must provide](../architecture-proposal.md#language-core-what-dsl-tools-must-provide-what-the-ide-adds)).
