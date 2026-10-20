# 8. Module boundaries are enforced by the build

- Status: Accepted
- Date: 2026-10-20
- Source: architecture proposal D7, §4

## Context

The layering (core, notation, runtime, bridges, ui, app, tools) only stays intact if violations are caught
automatically. Conventions in documents alone erode.

## Decision

- Gradle convention plugins in `build-logic/` and a version catalog (`gradle/libs.versions.toml`) give every
  module the same setup; `api` vs `implementation` is used deliberately.
- `verifyModuleDependencies` (part of `check` in every module) rejects project dependencies that violate the
  dependency matrix in `build-logic/.../ModuleDependencyRules.kt`; the settings fail for a module without it.
- Architecture tests (Konsist) fail the build if `core/*` or `notation/*` import Compose, Ktor, Docker or any UI
  package, and check the other import rules (fully qualified references included) and the package layout.

## Consequences

- `./gradlew build` fails on a boundary violation, locally and in CI.
- Adding a module requires adding it to the dependency matrix and the architecture-test module table.
- P0 implementation choice, not part of D7: `core/*` and `notation/*` also compile in Kotlin explicit API mode.
