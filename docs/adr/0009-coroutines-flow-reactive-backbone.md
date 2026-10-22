# 9. Reactive backbone: kotlinx.coroutines with Flow and StateFlow

- Status: Accepted
- Date: 2026-10-20
- Source: the architecture proposal's [reactive backbone decision](../architecture-proposal.md#reactive-backbone-kotlinxcoroutines-with-flow-and-stateflow)

## Context

The IDE is highly concurrent: debounced parsing per document, runtime WebSocket streams, container logs, bridges
and UI updates. The concurrency model must be uniform across layers and cancel cleanly.

## Decision

kotlinx.coroutines with `Flow`/`StateFlow` is the reactive backbone. Each open document is a structured-concurrency
scope; each runtime session likewise. There is no DI framework initially; components are wired explicitly in
`app/ide`.

## Consequences

- Document snapshots and the live model mirror are exposed as `StateFlow`.
- Closing a document or session cancels all of its work through its scope.
- Wiring in `app/ide` is plain Kotlin; a DI framework can be introduced later if the wiring grows unwieldy.
