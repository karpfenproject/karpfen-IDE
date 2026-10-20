# 10. karpfen-dsl-tools resolution: composite build or artifact

- Status: Accepted; partially supersedes [ADR 0003](0003-dsl-tools-is-the-language-core.md) (CI part) until dsl-tools is published
- Date: 2026-10-20
- Source: architecture proposal D2, §5.1 (dependency mechanics), §8 (pin dsl-tools), §9 item 4

## Context

The IDE depends on dsl-tools (ADR 0003); both repositories often change together. D2 and §5.1 have CI use published
artifacts, but dsl-tools is not published to a remote repository yet.

## Decision

- **Composite build**: `dslToolsPath` from `-PdslToolsPath`, else `local.properties` (git-ignored), else
  `../karpfen-dsl-tools`; a Gradle build there is included and substitutes the module (`using(project(":"))`).
- **Artifact**: otherwise (or an empty `dslToolsPath`): the catalog version from `mavenLocal()` (`io.karpfen` only).
- **CI, a temporary deviation from D2/§5.1**: CI clones dsl-tools at a pinned commit (`DSL_TOOLS_REF`, §8) to the
  default path and builds in composite mode, until dsl-tools publishes versioned artifacts.

## Consequences

- Cross-repository changes are a single local iteration. The settings log the active mode, though not on a
  configuration-cache hit (`--no-configuration-cache` or `dependencyInsight` then shows it).
- dsl-tools is not configuration-cache compatible yet: composite builds store no cache entry, and dsl-tools tasks
  invoked directly need `--no-configuration-cache` (until §5.1 item 6 lands).
