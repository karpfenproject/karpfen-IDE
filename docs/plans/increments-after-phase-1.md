# Increments after phase 1

Phase 1 delivered the text editor: language support for KMeta, KModel and KStates, a workspace with project analysis,
and the Compose workbench. The work continues in the increments below, in this order, except that the fifth came
before the fourth by the owner's choice. Each one ends with a green build, an adversarial review and fixes before the
next one starts.

1. **Conventions and phase-1 follow-ups.** The code base and the documentation follow the owner's documentation style:
   documentation lives in KDoc next to the code, there are no inline comments, and the markdown documents are a map
   that tells the reader where to look in the code. Environment descriptors become editable in the IDE with language
   support, and code completion moves to Ctrl+M on every platform, plus Ctrl+Space on Windows. This increment is
   complete.
2. **Editor support in karpfen-dsl-tools.** Diagnostics with source positions instead of exceptions, tolerant parsing,
   printers for the three languages, logging through SLF4J instead of printing to the console, and a package cleanup.
   The IDE then places dsl-tools errors precisely and shows dsl-tools output only on demand, forwarding errors. This
   increment is complete. karpfen-dsl-tools 2.0 lives on the branch feature/editor-support of the dsl-tools
   repository, to which the submodule points; that branch must be pushed to the dsl-tools repository before CI can
   build this repository.
3. **Runtime client and the runtime's IDE support.** The runtime gained, on its branch with IDE support, a model
   snapshot, a revision on every push, a subscription to the whole model of an environment, domain events that reach
   their listeners, and small fixes with regression tests. The IDE has a typed client of the runtime's HTTP and
   WebSocket API that keeps the lifecycle of each environment, deployment driven by an environment descriptor over the
   saved files, a mirror of the model behind a live observation switch, and event injection. Everything is tested
   against a fake runtime that answers only what each test programs and, on demand, against a real runtime in throwaway
   Docker containers. This increment is complete. The runtime's branch feature/ide-support, to which the karpfen-runtime
   submodule points, must be pushed to the runtime repository before CI can build this repository.
4. **Changes in the runtime repository.** Deleting environments, a version endpoint, configuration through environment
   variables and a published container image; and the bigger findings of the third increment, on which the owner
   decides: writing known objects by reference instead of nesting them, so that reference loops can be written, and
   what `NaN` and `Infinity` become in JSON; a queue per client instead of one blocking broadcaster; which socket of a
   client receives its pushes, and an acknowledged handshake; an error channel for engine failures; removing domain
   listeners and observatory registrations while an environment runs; pushes still queued when an environment stops;
   what restarting a stopped environment, a macro that changes an existing object and setting a scalar the model never
   wrote mean; expiring access keys and the events of stopped environments; authentication; reading uploads with
   dsl-tools 2.0, so that errors carry positions; and the entry block of a state that a join leaves in the next tick,
   which the engine skips today. Running the runtime's own tests needs the owner's permission.
5. **Runtime hosting and runtime UI.** Done before the fourth increment. The IDE hosts the runtime in an IDE-owned
   Docker container, one per workspace, whose image it builds on first use from the karpfen-runtime sources bundled
   with it, or attaches to a runtime started elsewhere by its URL; it never runs the runtime as a local process. A Run
   action saves every open file and deploys an environment descriptor of the workspace through a runtime session, and
   the Runtime tool window shows the runtime's status and log and the runs, each with its deploy steps, its problems,
   the active states, the observed model as a tree, the domain events and an event injector that edits a JSON payload
   prefilled from the event's payload type. When the IDE exits, it shuts down every session and stops its container.
   The hosts are tested against a fake Docker engine and, on demand, against the local Docker engine. This increment
   is complete.
6. **Blended statechart view.** A read-only statechart diagram with automatic layout, then a live overlay of the active
   states, then graphical editing that patches the text, then class and object diagrams.
7. **Bridges.** Declarative message mapping, MQTT, ROS through the rosbridge protocol, and the headless bridge runner.
8. **Depth.** Validation rules in dsl-tools, a language server for other editors, packaging and signing, and stepping
   the engine tick by tick.

Every repository the IDE needs is a git submodule of this repository, and all work happens inside this workspace:
karpfen-dsl-tools and karpfen-runtime are submodules.

Owner decisions that shape these increments: environment descriptors are edited inside the IDE; code completion uses
Ctrl+M everywhere and Ctrl+Space on Windows; dsl-tools output never lands on the primary console but is available on
demand with errors forwarded; time-budget tests are written but never run; documentation follows the style above; a
deployment uploads the saved files, the descriptor holds the deploy configuration, observation is a live switch and a
stopped environment is final; tests against a real runtime run on demand in throwaway containers, against a fake
that never simulates the runtime otherwise; and the runtime runs in an IDE-owned Docker container built from the
bundled runtime sources or at a URL, never as a local process.
