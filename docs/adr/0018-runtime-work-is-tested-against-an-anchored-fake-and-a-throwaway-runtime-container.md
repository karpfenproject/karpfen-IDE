# 18. Runtime work is tested against an anchored fake and a throwaway runtime container

- Status: Accepted; built in the third increment after phase 1
- Date: 2026-11-04
- Source: the owner's decisions of 2026-11-03 on tests against a real runtime, and the owner's decision during the
  increment that the fake runtime never simulates the runtime

## Context

The runtime client and the sessions have to work against the real runtime, whose behaviour lives in another
repository and changes with it. Tests on every build must not need a runtime, Docker or Python, but a fake that
imitates the runtime would only test the client against the fake's own idea of the runtime. Real runs are slower than
the runtime's tick delay, the runtime may have bugs, and the machine the tests run on has Docker objects of other
projects that must never be touched.

## Decision

- The client reaches the runtime through seams the IDE owns, one for HTTP and one for the WebSocket, so every test
  chooses what stands behind them.
- The fake runtime in the protocol's test fixtures never simulates the runtime: it only returns the responses and
  frames each test programs. The goldens of the runtime's wire format and source anchors, which find every fact the
  client relies on verbatim in the runtime branch's source, keep the client's view of the wire honest.
- Scenario classes hold the behaviour the IDE relies on and run only against a real runtime. Failures only a fake can
  produce, such as a lost push or a runtime without IDE support, are ordinary tests against the fake.
- A trap-guarded script builds the runtime image from the submodule and runs each test run in its own labelled,
  throwaway container, which it removes when the run ends, also when it fails. It only ever removes what carries its
  labels, and its cleanup reports what stays.
- Tests against a real runtime carry their own tag and run only in their own task. Unlike the tag of time-budget tests,
  which are never run, this tag has a switch on purpose: the script runs the task on demand.
- The synthesized test models need no Python, except the one model that creates objects at run time, which only
  Python can do; Python runs only inside the container.
- Waits are eventual with generous bounds, and absence is proven by a cause, never by a time window.

## Consequences

- No build, check or CI run starts a runtime or Docker; the architecture tests keep the tag, the task and the script
  apart from them.
- Moving the karpfen-runtime submodule makes the anchors fail exactly where the runtime changed: the catalogue, the
  goldens and the hardcoded responses change together, the live suite runs, and then the anchors follow.
- The [README](../../README.md) says how to run the live tests and clean up; the [codebase guide](../codebase-guide.md)
  maps the fake, the goldens, the scenarios and the script.
