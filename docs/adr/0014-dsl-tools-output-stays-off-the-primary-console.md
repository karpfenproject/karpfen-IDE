# 14. dsl-tools output stays off the primary console

- Status: Accepted; to be built in the second increment after phase 1
- Date: 2026-10-22
- Source: the owner's decision after phase 1 on silencing karpfen-dsl-tools ("I would not eat away the output but not
  add it to a primary console output")

## Context

karpfen-dsl-tools prints to the standard streams while it parses and converts. Shown on a primary console, such as
the IDE's process output or the report of `karpfen validate`, these lines bury what the user asked for; dropped, they
hide information that can explain a failure. The command-line tool currently drops them while it validates, and the
IDE leaves them on its process output.

## Decision

- What dsl-tools prints never lands on a primary console of the IDE or the command-line tool.
- It is not thrown away either: it is available on demand, for example in a log or a view the user opens, and the
  errors among it are forwarded to where the user sees problems.
- The cleanest path is in dsl-tools itself, which is to report diagnostics with source positions and log through SLF4J
  instead of printing; the IDE then routes its log like any other.

## Consequences

- The [plan of the increments after phase 1](../plans/increments-after-phase-1.md) schedules the change for its second
  increment, together with the editor support in dsl-tools.
- Until then, the command-line tool keeps dropping the output while it validates (`StandardOutputGuard`), which this
  decision replaces.
