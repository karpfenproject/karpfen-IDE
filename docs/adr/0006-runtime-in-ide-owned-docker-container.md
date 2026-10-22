# 6. The runtime runs in an IDE-owned Docker container by default; wire protocol only

- Status: Accepted (owner asked, accepted)
- Date: 2026-10-20
- Source: the architecture proposal's [runtime decision](../architecture-proposal.md#the-runtime-runs-in-an-ide-owned-docker-container-by-default-wire-protocol-only), its
  [runtime integration](../architecture-proposal.md#runtime-integration-runtime-modules) and the accepted recommendation that the runtime repository publishes the
  image ([decisions record](../architecture-proposal.md#decisions-record))

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
- Some [runtime gaps listed in the proposal](../architecture-proposal.md#gaps-the-ide-will-run-into-small-changes-in-your-own-repos) are worked around client-side until small upstream
  endpoints land.
