# 16. The runtime client talks to the runtime with IDE support

- Status: Accepted; built in the third increment after phase 1; partially supersedes the consequence of
  [the runtime decision](0006-runtime-in-ide-owned-docker-container.md) that names the client's libraries
- Date: 2026-11-04
- Source: the owner's decisions of 2026-11-03 on the runtime client: a snapshot endpoint and a live observation switch
  in the runtime, a client that depends on no IDE module, and small runtime bugs fixed on a runtime branch

## Context

The IDE deploys environments to the karpfen runtime, observes their models and injects events. The runtime as it was
could not support that reliably: it had no way to read a whole model, its pushes carried nothing that ordered them
against a read, it could not stream the whole model to a client, its domain listeners never received anything, and it
let clients repeat lifecycle calls with hazardous results. Its WebSocket handshake is never acknowledged, and its JSON
writes `NaN` and `Infinity` as bare literals.

## Decision

- The runtime gains, on its branch with IDE support, a model snapshot read between two ticks, a revision on every object
  and stack push, a subscription to the whole model of an environment while it runs, domain events that reach the
  domain listeners, and small, clear bug fixes with regression tests. Bigger findings go to the owner, and the IDE copes
  with them meanwhile.
- runtime-protocol is a typed client of every route and of the WebSocket, behind seams the IDE owns, with Ktor confined
  to their adapters. It depends on no other module of the IDE and declares its API explicitly.
- The client keeps the lifecycle of each environment itself, refuses calls the runtime would mishandle, such as a
  second activation or restarting a stopped environment, and reconciles its phase with the one the runtime reports in a
  snapshot.
- It reads the runtime's JSON itself: strict JSON plus the three non-finite literals, with the order of the fields
  kept.
- It connects right after creating an environment, long before activation, because the handshake is never
  acknowledged; the model subscription, which the runtime grants only to an attached session, is the point from which
  every later push arrives.
- Before it creates anything, a deployment checks that the runtime has IDE support.

## Consequences

- The IDE needs the runtime's branch with IDE support. karpfen-runtime is a git submodule of this repository that points
  at it, as [required repositories are git submodules](0015-required-repositories-are-git-submodules.md) asks; the
  branch must be pushed before CI can check it out.
- The client uses Ktor only in its adapters and no serialization library, which replaces what
  [the runtime decision](0006-runtime-in-ide-owned-docker-container.md) said about the client's libraries.
- The [codebase guide](../codebase-guide.md) maps the client, starting at `RuntimeClient`; the KDoc of the handle,
  the connection and the catalogue documents the runtime's behaviour the client relies on.
