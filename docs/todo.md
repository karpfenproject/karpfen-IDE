# Open points

What is known to be missing, unfinished or undecided. The [developer notes](devdoc.md) explain the design these points
refer to.

## In the IDE

### Editor and language support

- There is no folding UI, no find-usages UI, no rename refactoring, no command that reformats a file and no split
  editor. dsl-tools 2.0 has printers for the three languages, but the IDE does not use them.
- The caret model holds a list of carets, but the editor offers no way to add a second one.
- The undo history has no limit.
- On a German Windows layout, Ctrl+Shift+7 does not reach Toggle Comment.
- The theme menu has no entry for following the system. The zoom controls have no commands and no shortcuts.
- A language server does not exist. It would put core-lang behind the Language Server Protocol for other editors. Its
  path `tools/lsp-server` is not in the settings, and `lsp4j` in the version catalog is unused.

### Diagrams

- The diagrams change the text through their context menus and code cells. A split is added with two targets and a
  join with two sources, and more are added one at a time. The macros of a state machine are edited as text only.
- The type of a new object in the object diagram is typed in. The diagram does not know the types of the metamodel,
  so it cannot offer them.
- An opened code cell has the code editor with colours, but without completion, hover and checks, because it holds
  a piece of a file. It closes without saving when the text changes underneath it.
- A rename is found by comparing the diagram before and after a change of the text. Renaming one element while
  adding or removing another in the same edit is not recognized, and the renamed element is placed as new.
- A change of the layout by hand cannot be undone, and there is no command that goes back to the automatic layout.
  Deleting the `.vis` file by hand does that.
- Renaming or deleting a model file leaves its `.vis` file behind under the old name.
- A state name that contains `/from:` or `/to:`, or ends in `#` and digits, can make the name of a line in the
  `.vis` file ambiguous when a state is renamed in the diagram. The lines then lose their waypoints.
- The menu of a state has no entry that adds a timeout. A timeout is written in the tick limit cell.
- The history badge of a state is not part of its size, so in a narrow state with nested states it can lie over the
  markers of the code cells.
- In a statechart a label may cover another line. The class and object diagrams move a label to the free side.
- The view mode of a file, Text, Split or Diagram, is not stored in the project file.
- The class diagram shows the types of one file only, so a relation to a type of another metamodel file is a row and
  not a link.
- Dragging and the context menu were tried in tests only for the class and object diagrams, not in the window.

### Running models

- After a restart the IDE does not adopt the environments it left on a runtime.
- Deployment problems are shown in the run, not in the Problems view.
- The descriptor template still writes the `observe` key, which deployment ignores.
- The descriptor keys `runtime.image`, `runtime.pythonPackages` and `bridges` are accepted but ignored. So `EVAL`
  blocks can import only the Python packages of the runtime image, and a run offers every bridge file of the project.
- A process that is killed outright, for example with `kill -9` or a forced quit, leaves its runtime container
  running. Only a run in the same folder adopts or removes it. Nothing looks for the containers of other folders
  whose IDE is gone.
- The runtime container runs without limits on CPU or memory.
- With tracing on, the runtime also writes the whole trace, tick lines included, to a file in the container. The file
  grows until the container is removed, which happens when the IDE closes the folder.
- When the watcher gives up on a container that still runs, the next start adopts it and follows its log from the
  container's start again. The Log pane then probably shows earlier lines twice. This follows from the code and has
  not been seen.
- Recording the events of a run and replaying them, which the runtime offers, has no place in the IDE. A run can
  only be captured to a file as text.
- A captured file holds the log of the whole runtime while its run lives. With two runs at once, each file also holds
  the lines of the other run. Lines that the runtime writes before the deployment are not in the file.
- The times in the simplified log are those of the runtime's clock, which is UTC in the IDE's container, not the
  local time.
- Stopping a run without a window that was started through Gradle makes the Gradle task fail, because the process
  ends with the status of its signal.
- An embedded runtime does not exist. Its path `runtime/runtime-embedded` is not in the settings.

### Bridges

- The ROS bridge was tested against a stand-in for a rosbridge server only, not against a ROS system.
- A bridge in the IDE hears changes of the model only while the run observes it.
- A password in the settings of a bridge file is stored as plain text in the project.
- There is no scripting hook for custom transforms and no native ROS transport.

### Workspace, window and command-line tool

- An exception while the window is composed or drawn freezes the window. It is written to the log, but no error dialog
  appears. Only the diagram view catches its own failures.
- There is no Refresh command. After the file watcher failed, changes made outside the IDE show up only when a file
  operation in the Project tree refreshes the workspace.
- Whether files next to a descriptor but not named by it should form an implicit context is undecided.
- A run without a window has no time limit and no way to end by itself when the state machines are done. It ends
  with a signal only.
- A run without a window prints no warnings of the validation, and with a runtime at a URL it has no log lines to
  print, only the active states.
- On Windows the packaged application is built without a console, so the commands without a window probably print
  nothing there. Nobody has tried it.
- The small command-line tool `karpfen` cannot run a project. Only the IDE itself can, with `karpfen-ide run`.
  tools/cli declares a dependency on runtime-session that it does not use.
- A validation reports only in its dialog. Its findings across files are not in the Problems view and not underlined
  in the editor, and the dialog does not lead to their places.
- The copies of the dsl-tools example in the test fixtures are older than the example itself. The copy of the robot
  still sets relations with `SET`, which the verifier reports, so validating the fixtures finds four errors.
- `karpfen validate` does not read `.karpfenproject`. It checks with every language feature on, so it accepts a
  project that the IDE blocks because of a feature that is off. The format of the feature lines lives in
  ui-workbench, which the command-line tool does not use.
- A `.kstates` file that belongs to no modeling context is not checked for features that are off, because only the
  files of a context are read by dsl-tools.
- Performance work has not started, for example on the model mirror and on snapshots of large models. The tests tagged
  `budget` hold the targets.

### Checks that dsl-tools does not make

dsl-tools checks each text in its readers and a project as a whole in its verifier, as its guide
`guides/verification.md` lists. Still missing:

- The paths that the runtime reads for the missing arguments of a macro call.
- A read of an event field in a project without event definitions, which always fails at run time.
- Whether a metamodel can be instantiated at all, for example when two types must contain each other.

## In the runtime

- There is no channel that reports engine errors to the client. The IDE only sees that the engine halted or went
  silent and points to the runtime log.
- Environments cannot be deleted, so they pile up in a long-running runtime. Access keys never expire, and events sent
  to a stopped environment are queued anyway.
- The engine skips the entry block of a state that a join leaves in the next tick. The test for it in
  `ObservationScenarios` is disabled until the runtime is fixed.
- After the `ENTRY` block of an outer state the engine also checks the transitions of the nested states it has not
  entered yet. If one fires, the `ENTRY` blocks of those nested states never run.
- A transition into a state with nested states stops at that state and does not go on to its `INITIAL` nested state.
  A transition to the state itself enters the state around it again as well. Both are documented, and nobody has
  decided whether they are wanted.
- `NOT LOOPING` has no effect on a `SPLIT` or a `JOIN`. An event that fires a `JOIN` is not scoped to the target, and
  the same event can fire the same join again.
- Histories and tick limits only work in an environment whose feature is switched on. The IDE switches on what the
  project has on. Another client has to do that by itself.
- A history is never forgotten while the engine runs, and a state that remembers a state never rests as the innermost
  state, so its `DO` block does not run any more.
- A breakpoint override names a transition by the text form the engine prints for it, which two transitions can
  share.
- The features use `kapt` to register themselves, which copies every comment into generated Java. A comment with a
  backslash followed by `u` breaks the build there.
- Building the runtime rewrites the generated parsers in its nested dsl-tools checkout with other line endings. The
  IDE packs the runtime sources from the working tree, so such a build changes the hash that names the runtime
  image. `git -C karpfen-runtime/karpfen-dsl-tools checkout -- .` brings the files back.
- Known objects are written nested instead of by reference, so reference loops cannot be deployed.
- `NaN` and `Infinity` are written as bare literals, which is not JSON. What they should become is undecided.
- One broadcaster serves all clients, so one slow client can stall the others. A queue per client would fix it.
- The handshake is never acknowledged. Several sockets with one client id get pushes only on the first.
- Domain listeners and observation registrations cannot be removed while an environment runs. So domain events keep
  flowing while observation is off. Whether observation off should silence them too is undecided.
- Pushes still queued when an environment stops are dropped.
- A macro that changes an existing object sends no push. Setting a scalar that was never written does send one.
- The runtime pushes nothing when a transition fires or an event is consumed, so a live view cannot show either.
- Starting a stopped environment restarts it from the initial states over the changed model. The stop closed its
  sockets, so clients must connect again. The runtime does not document this, and the IDE never does it.
- There is no version endpoint, no published image, no configuration through environment variables and no
  authentication. Access keys are predictable, and the runtime prints them on its console.
- The runtime reads uploads with the dsl-tools 1.0 parser from its own nested submodule, so upload errors carry no
  positions. That parser reads a tick limit whose number is not whole as a state without the limit and says nothing.
  The IDE's editor reports it.
- The runtime's documentation of `eventConsumptionOnFire` does not match what the engine does.
- A JSON event payload with a repeated key or a scalar of the wrong type silently becomes null as a whole, and every
  relation in a JSON payload creates new objects, `knows` included. The event still fires, without its payload. The
  injector's checks in `PayloadDiagnosticCodes` catch these cases before an event is sent.
- State names are global in the runtime, and its lookups take the first match depth-first. A nested state that shares
  the name of a top-level state therefore makes the reported stack ambiguous. `StackSegmenter` explains the effect.
- The runtime never reads the type a state machine names after `ATTACHED TO`. It treats `_` as an ordinary object key
  and keeps the last of two objects with the same key. The deploy checks report both before a run.
- The branch with IDE support changed what the runtime sends and accepts. An environment is activated at most once,
  and pushes carry revisions and domain events. Nobody has checked whether other clients of the runtime rely on the
  old behaviour. That matters before the branch is merged, and whether to merge it is undecided.

## In the build and on other platforms

- There is no continuous integration. The build and the tests run only on the developer's machine.
- Gradle prints a deprecation warning about `Configuration.setVisible`. It most likely comes from a plugin applied in
  app/ide. The warning says the build will not work with Gradle 10. Check it again when the Compose Gradle plugin is
  updated.
- Only macOS has been built and tried. Linux and Windows are not built at all. Which platforms to ship, and signing
  for macOS and Windows, are undecided.
- The image build leaves base images and a build-stage image behind. A Dockerfile with separate dependency layers
  would make image rebuilds faster.
- When the runtime repository publishes an image, pulling it instead of building it needs a new decision.
- Podman, Colima and Rancher Desktop offer the Docker API on a local socket and should work through `DOCKER_HOST` or a
  Docker context. Nobody has tried them.
