# 6. The runtime runs in an IDE-owned Docker container by default; wire protocol only

- Status: Accepted (owner asked, accepted)
- Date: 2026-10-20
- Source: architecture proposal D5, §5.6, §9 item 6

## Context

Users must deploy and experiment against a karpfen runtime without handling Docker or configuration files
themselves. IDE and runtime should remain independently versioned.

## Decision

The IDE talks to the runtime exactly like any other client, over HTTP/WebSocket. By default the IDE owns one
runtime container per project workspace and manages its whole lifecycle (image pull, config injection without
bind mounts, start, health, logs, stop, cleanup and reconciliation via labels). Alternatives behind the same
`RuntimeHost` interface: attach to an external runtime URL, or run the distribution as a host process. In-process
embedding of the execution engine is a later, optional module. The runtime image is published multi-arch from
the runtime repository's CI; the IDE only pulls and pins it.

## Consequences

- `runtime-host` is the only module that may use the Docker client (docker-java), enforced by architecture tests.
- `runtime-protocol` is a typed client for every endpoint and the WebSocket session (Ktor client, kotlinx.serialization).
- Some runtime gaps (§1.4) are worked around client-side until small upstream endpoints land.
