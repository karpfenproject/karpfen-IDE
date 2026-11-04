# 17. Deployment uploads the saved files and the descriptor holds the deploy configuration

- Status: Accepted; built in the third increment after phase 1
- Date: 2026-11-04
- Source: the owner's decisions of 2026-11-03 on the deploy input, the event domains, observation, reference loops, the
  tick delay and the event lifetime, and running again

## Context

A deployment reads an environment descriptor and the files it names and uploads them to the runtime. Open editors may
hold unsaved text, the runtime reads the files with an older parser than the IDE, and the deploy configuration, the
tick delay and the event time to live, needed one clear home. The IDE also had to decide which event domains to listen
to, what a model the runtime cannot write means for a deployment, how observing a running environment works and what
happens when an environment is run again.

## Decision

- A deployment uploads the saved state of the files: preparing it saves every open document first, and the problems
  gate checks the diagnostics of exactly the analyzed texts that are uploaded. A file that still differs from its saved
  state, because it was edited meanwhile, blocks the deployment.
- The environment descriptor holds the deploy configuration. An absent tick delay or event time to live means the
  default the descriptor format defines, 50 ms and 3000 ms, and every deployment sends both, so the runtime's own
  defaults never apply to a deployment of the IDE.
- A deployment listens to every event domain the state machines use. The event catalog marks a domain as declared when
  the events file declares one of its events, and as only used otherwise.
- A model in which an object reaches itself through `has` and `knows` relations is not deployed: the runtime writes
  known objects as nested objects and cannot write such a model.
- Observation is a switch. On subscribes to the whole model, reads a snapshot first and then applies every push in
  revision order, reading a snapshot again after a gap or a reconnect; off means no observation traffic, and the model
  changes only when it is pulled.
- A stopped environment is final; running again deploys a fresh environment. The runtime cannot delete environments,
  so the IDE records every environment it leaves on a runtime in a ledger.

## Consequences

- The descriptor's `observe` key is no longer read; a deployment says so with an info.
- Domain events keep coming while observation is off, because the runtime freezes the listeners at activation; whether
  off should silence them too is an open question to the owner.
- The command-line tool has no deploy command; the session's preparation is the base of a later one and of the IDE's
  Run action.
- The [codebase guide](../codebase-guide.md) maps the deployment, starting at `RuntimeSessions`.
