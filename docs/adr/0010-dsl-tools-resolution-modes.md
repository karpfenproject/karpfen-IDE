# 10. karpfen-dsl-tools resolution: composite build or artifact

- Status: Accepted
- Date: 2026-10-20
- Source: architecture proposal §5.1 (dependency mechanics), §9 item 4

## Context

The IDE depends on karpfen-dsl-tools (ADR 0003), and both repositories will change together often. dsl-tools is
not published to a remote Maven repository yet; its Gradle coordinates are `io.karpfen:karpfen-dsl-tools`.

## Decision

`settings.gradle.kts` supports two modes:
- **Composite build**: `dslToolsPath` comes from `-PdslToolsPath`, else `local.properties` (git-ignored), else
  `../karpfen-dsl-tools`. A Gradle build there is included and substitutes the module (`using(project(":"))`).
  On the owner's machine `../karpfen-runtime/karpfen-dsl-tools` is the initialized submodule.
- **Artifact**: otherwise (or with an empty `dslToolsPath`), the catalog version is resolved from the repositories.
  Locally that is `mavenLocal()`, restricted to the `io.karpfen` group; a published repository later.

## Consequences

- Cross-repository changes are a single local iteration; the build log states the active mode.
- Until dsl-tools is published, CI clones it next to the checkout and uses the composite mode.
- The dsl-tools build is not configuration-cache compatible yet; composite builds therefore run without storing a
  configuration cache entry (graceful degradation), artifact builds use the cache.
