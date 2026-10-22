# Increments after phase 1

Phase 1 delivered the text editor: language support for KMeta, KModel and KStates, a workspace with project analysis,
and the Compose workbench. The work continues in the increments below, in this order. Each one ends with a green build,
an adversarial review and fixes before the next one starts.

1. **Conventions and phase-1 follow-ups.** The code base and the documentation follow the owner's documentation style:
   documentation lives in KDoc next to the code, there are no inline comments, and the markdown documents are a map
   that tells the reader where to look in the code. Environment descriptors become editable in the IDE with language
   support, and code completion moves to Ctrl+M on every platform, plus Ctrl+Space on Windows.
2. **Editor support in karpfen-dsl-tools.** Diagnostics with source positions instead of exceptions, tolerant parsing,
   printers for the three languages, logging through SLF4J instead of printing to the console, and a package cleanup.
   The IDE then places dsl-tools errors precisely and shows dsl-tools output only on demand, forwarding errors.
3. **Runtime client without Docker.** A typed client for the runtime's HTTP and WebSocket API, deployment driven by an
   environment descriptor, a live mirror of the model, and event injection, all tested against fakes.
4. **Changes in the runtime repository.** A model snapshot endpoint, wildcard object observers, deleting environments,
   a version endpoint, configuration through environment variables, and a published container image. Running the
   runtime's own tests needs the owner's permission.
5. **Runtime hosting and runtime UI.** Attaching to a running runtime, starting it as a local process or as an
   IDE-owned container, a Run action, a runtime tool window with logs, and an event injector. Real runtime starts and
   Docker need the owner's permission; everything is built and tested against fakes first.
6. **Blended statechart view.** A read-only statechart diagram with automatic layout, then a live overlay of the active
   states, then graphical editing that patches the text, then class and object diagrams.
7. **Bridges.** Declarative message mapping, MQTT, ROS through the rosbridge protocol, and the headless bridge runner.
8. **Depth.** Validation rules in dsl-tools, a language server for other editors, packaging and signing, and stepping
   the engine tick by tick.

Owner decisions that shape these increments: environment descriptors are edited inside the IDE; code completion uses
Ctrl+M everywhere and Ctrl+Space on Windows; dsl-tools output never lands on the primary console but is available on
demand with errors forwarded; time-budget tests are written but never run; and documentation follows the style above.
