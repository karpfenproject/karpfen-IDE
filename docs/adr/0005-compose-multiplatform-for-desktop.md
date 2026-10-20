# 5. UI toolkit: Compose Multiplatform for Desktop with a custom editor and diagram canvas

- Status: Accepted (decided by the owner)
- Date: 2026-10-20
- Source: architecture proposal D4, §5.8, §6

## Context

The IDE needs a native desktop UI with a code editor that can also be embedded in diagram cells, a custom
diagram canvas, and tool windows. Compose Multiplatform for Desktop and JavaFX 25 LTS were compared (§6):
Compose fits Kotlin and coroutines natively and has hot reload and strong momentum; JavaFX offers RichTextFX
and a retained scene graph but no hot reload and a smaller community.

## Decision

The UI uses Compose Multiplatform for Desktop (JVM) with a custom code editor and a custom diagram canvas. Compose
is confined to `ui/*` and `app/`; every other module is UI-agnostic. JavaFX is not used.

## Consequences

- The code editor (virtualized lines, caret, gutter, highlighting, completion) is built in-house; a
  `BasicTextField`-based implementation is only an acceptable stopgap behind the editor interface.
- Packaging uses the Compose Gradle plugin (dmg/msi/deb).
- Architecture tests reject Compose, AWT and Swing outside `ui/*` and `app/`, and JavaFX anywhere (ADR 0008).
- Jewel may provide IntelliJ-style widgets but must be wrapped because its API is unstable.
