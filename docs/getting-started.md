# Getting started

This guide explains what karpfen IDE does and walks you through a first run of a small model, a lamp with a wall
switch. Plan about 15 minutes, plus a few minutes once for building the runtime image. Keys are written for macOS
first and for Windows and Linux second, as in Cmd+S / Ctrl+S.

## The idea

karpfen is a small family of modeling languages. A system is described by a few text files:

- The metamodel (`.kmeta`) declares types. A type has properties (`prop`), objects it contains (`has`) and objects it
  refers to (`knows`). The last type in the file is the root type, the type of the model's outermost object.
- The model (`.kmodel`) creates objects of these types. Every object has a key, such as `lamp`.
- A state machine (`.kstates`) describes behaviour. It is attached to one object of the model. Entering a state runs
  actions that change the model. Transitions wait for events and conditions. Actions and conditions can compute
  values in `EVAL` blocks, which hold Python. The example here uses them only for small sums and comparisons.
- The event definitions are a second metamodel. Each type in it names an event, and the properties of the type are
  the payload of that event. The state machines say in which domain they expect an event, as in
  `EVENT("switch", "press")`.
- The environment descriptor (`.kenv.json`) ties it all together. It names the metamodel, the events, the model and
  each state machine with the object it is attached to. It can also set the tick delay and how long events live.

The karpfen runtime is a server that runs such a system. It loads the files into an environment and then works in
ticks. In every tick it runs all attached state machines once, and then it waits for the tick delay. The state
machines change the model as they go. Events can be sent into the environment from outside. An event waits until a
state machine takes it or until its time to live runs out.

karpfen IDE is an editor for these files and a front end for the runtime. It checks the files while you type, with
karpfen-dsl-tools, the language library of karpfen. It runs a descriptor in a runtime with one command. While the
model runs, it shows the model and the active states as they change, and it lets you send events with a payload.

## The modes

### Editing

Open a folder, and the Project tool window shows its files. The editor colours the text and underlines errors within a
second. The Outline shows the structure of the active file. The Problems tool window lists the problems of the whole
folder. It stays closed while there is nothing to show. Its label `Problems` at the left of the status bar gets a red
icon and the number of problems when there are some, and a click on the label opens the list. Resting the mouse on a
name shows what it is. The karpfen files of one folder form a modeling context,
which the IDE checks as one system. Without a descriptor, the events must be in a file named `EVENTS.kmeta`, and the
metamodel is the other `.kmeta` file of the folder. A descriptor makes the context explicit: the IDE then checks
exactly the files it names. Descriptors are edited like the other files. Completion offers their keys, the files of
the folder and the object keys of the model. Cmd+click / Ctrl+click on a file name opens that file. To create a
descriptor, right-click a folder in the Project tool window and choose New Environment File.

| Action | macOS | Windows and Linux |
|---|---|---|
| Complete Code | Ctrl+M, with the Control key | Ctrl+M, on Windows also Ctrl+Space |
| Go to Definition | Cmd+B or Cmd+click | Ctrl+B or Ctrl+click |
| Find and Replace | Cmd+F and Cmd+R | Ctrl+F and Ctrl+R |
| Go to Line | Cmd+L | Ctrl+G |
| Toggle Comment | Cmd+/ | Ctrl+/ |
| Save and Save All | Cmd+S and Cmd+Option+S | Ctrl+S and Ctrl+Alt+S |
| Run and Stop | Shift+F10 and Cmd+F2 | Shift+F10 and Ctrl+F2 |

### Running in the IDE's Docker container

This is the default. Run, in the Run menu, deploys a descriptor to a karpfen runtime in a Docker container that the IDE
owns. Each opened folder gets its own container, which listens only on `127.0.0.1`. The first time, the IDE builds the
runtime image from the runtime sources that come with it. That takes a few minutes and happens once. Later runs reuse
the image. The image has Python 3 with numpy, scipy, sympy, pandas, regex, python-dateutil and chardet, which `EVAL`
blocks can import. Start runtime and Stop runtime in the first line of the Runtime tool window start and stop the
container without a run. When you close the folder or the IDE, the IDE stops the container and removes it. The image
stays.

Run picks the descriptor in the active editor, or the only descriptor of the folder. If there are several, it asks.
It first saves all open files, because it uploads the saved files. A run whose files have errors stops before it
reaches the runtime and lists the errors instead.

### Running against a runtime at a URL

You can also start a karpfen runtime with IDE support yourself, like the one in the karpfen-runtime submodule. On macOS
and Linux, `runtime/live-runtime.sh up` builds it, starts it in a throwaway container and prints its URL, and
`runtime/live-runtime.sh down` removes it. In the first line of the Runtime tool window, click URL, enter the URL and
click Apply. The default is `http://127.0.0.1:8080`. The IDE checks that the runtime answers and has IDE support. It
never stops a runtime it did not start, and its Log pane cannot show that runtime's output. Click Docker to go back.
Switching stops the live runs, and the IDE keeps your choice in its settings.

### While a model runs

The Runtime tool window opens with every run. Runtime in the View menu and the runtime status in the status bar open
it too. It takes the whole width of the window. Its first line shows what the runtime is doing. Its second line lists
the runs, each with its phase, next to the buttons Run again, Stop and Clear finished. Below that, three panes show the
selected run next to each other, so you see what an event does while you send it.

Model shows the model as a tree. Expand all and Collapse all open and close every object at once. With observation on,
the runtime pushes every change and the tree follows live. Observation always covers the whole model, and the IDE
listens to every event domain the state machines use. With observation off, the model stays as it is until you click
Pull, which reads one snapshot.

Events holds the form that sends an event. Choose a domain, then an event, then fill in the payload. The domains with
events that the events file declares come first, in bold. The domains that only the state machines use follow and are
marked as such. For an event with a payload type, the form fills in a JSON template and checks your payload as you type.
The Send button names the event and is enabled while the payload has no error. Below the form is the list of the events
the environment has published, newest first.

Log starts with the active states of each state machine and one line about the deployment. A click on that line shows
the deploy steps. Below is the log of the runtime. With Simplified switched on, it shows only what the state machines
do, one step per line, such as `lamp  off -> on`, and the errors. With Simplified switched off, it shows everything the
runtime prints.


## Walkthrough: the lamp

The folder `examples/lamp` holds a small system. A living room has a lamp and a wall switch. The lamp goes from off to
on and back with each press of the switch. It always comes on at the full power of its bulb, which is 40 W. While it is
on, a dimmer turns it up and down in steps of 10 W, between 0 W and the full power. Switching it off forgets the
dimming. It can be smashed at any time, which breaks it and throws away its spare bulb. The switch has its own state
machine that moves up and down with each press. The descriptor sets a tick delay of 500 ms, so you can watch each change
happen. It also lets an event wait up to 5 seconds for a state machine to take it.

### 1. What you need

- A JDK 21. Gradle also runs on JDK 17 or newer and downloads a JDK 21 for the IDE if none is installed.
- Docker Desktop or another local Docker engine, running.
- The repository with its submodules:

  ```bash
  git clone --recurse-submodules https://github.com/karpfenproject/karpfen-IDE.git
  cd karpfen-IDE
  ```

  In an existing clone, run `git submodule update --init --recursive`.

### 2. Start the IDE on the example

From the repository root, on macOS and Linux:

```bash
./gradlew :app:ide:run --args="$PWD/examples/lamp"
```

On Windows, in a command prompt, run `gradlew.bat :app:ide:run --args="%CD%\examples\lamp"`. The path must be
absolute, because Gradle starts the IDE in the folder `app/ide`. The IDE ignores a folder argument that is not a
directory and shows its start page, where Open Folder… lets you pick `examples/lamp`. The first build takes a few
minutes, and the command keeps running while the IDE is open.

Expected: the window opens on the folder. The Project tool window lists six files. The status bar shows the label
`Problems` without an icon, 0 errors, 0 warnings and `Runtime: stopped`.

### 3. Look around

Double-click `lamp.kstates`. Expected: it opens in a tab with coloured keywords and strings, and the Outline lists its
states and transitions.

- Cmd+click / Ctrl+click on `Lamp` in `STATEMACHINE ATTACHED TO "Lamp"`. Expected: `lamp.kmeta` opens at the type.
- Open `lamp.kenv.json` and rest the mouse on the key `tickDelayMs`. Expected: a tooltip explains the key.
- In `lamp.kstates`, delete the `{` after `STATE "broken"`. Expected: within a second a red underline appears, and
  the label in the status bar turns into `Problems: 1` with a red icon. Click it to see the error in the Problems tool
  window. Undo with Cmd+Z / Ctrl+Z. Expected: everything is clean again, and the Problems tool window closes.

### 4. Run it

Choose Run in the Run menu, or press Shift+F10. On a Mac keyboard you may have to hold Fn for the F keys.

Expected: the Runtime tool window opens at the bottom. Its second line shows a run named `lamp`, whose label moves
from `preparing` to `waiting for the runtime`, `deploying` and `running`. The first line says what the runtime is doing.
On the first run it shows `Docker: building the runtime image` with the current build step for a few minutes. Then
come `creating the container`, `starting the container` and `waiting for the runtime`, and at last
`Docker: running at 127.0.0.1:` with a port. The status bar says `Runtime: running`.

The Log pane on the right then starts with the active states, `lamp: off` and `wallSwitch: down`, and the line
`Deployment done` with the number of steps. Click that line. Expected: every step is `done`, among them
`Setting the tick delay to 500 ms`. The simplified log below shows that the engine started and that the lamp starts in
`off` and the switch in `down`. The Model pane on the left says `Observation on` and `live at revision` with a number.

In the Model pane, click Expand all. Expected: the tree opens down to the bulbs. The lamp shows `mode = "off"`, the bulb
`maxWatts = 40`, `watts = 0` and `lit = false`, and the switch `position = "down"`. The lamp's row `control` links to
`wallSwitch`. Click the link to jump there.

If Docker is not running, the run turns `failed` and the first line turns red. It names the problem and ends with
`Start Docker Desktop or the local Docker engine, then try again.` Do that and run again.

### 5. Play with it

The form in the Events pane starts on the domain `service` with its event `smash`. After each Send, look at the Model
pane to the left and at the Log pane to the right.

1. Click the domain `switch`. The event `press` is selected, and the button reads `Send press`. Click it. Expected:
   within a second the lamp goes on at full power. The log shows `lamp  off -> full` and `wallSwitch  down -> up`, and
   the active states above it read `lamp: on > full` and `wallSwitch: up`. In the Model pane the lamp has `mode = "on"`,
   the bulb `lit = true` and `watts = 40`, and the switch `position = "up"` and `presses = ["up"]`. The event list below
   the form shows the press with the source `external`.
2. Click the event `dim`. It turns the dimmer one step. The payload editor shows a JSON object with `"bright": false`,
   which means one step dimmer. Replace `false` by `3`. Expected: the form shows an error, because `bright` is a
   boolean, and the button is greyed out. Write `false` again and click `Send dim`. Expected: the log shows `lamp  on ->
   dimmer`, and the bulb has `watts = 30`. Send it three more times. Expected: the bulb goes down to `watts = 0`. One
   more `Send dim` changes nothing, because the bulb cannot go below 0 W.
3. Change the payload to `"bright": true` and send it. Expected: the log shows `lamp  on -> brighter`, and the bulb has
   `watts = 10`. Each further send adds 10 W until the bulb is back at 40 W, its `maxWatts`. After that a send changes
   nothing.
4. Dim the lamp a step or two, then click `press` and send it. Expected: the lamp goes off. The active states read
   `lamp: off` and `wallSwitch: back down`, and the bulb has `lit = false` and `watts = 0`. Send `press` again.
   Expected: the lamp comes on at `watts = 40` again, because switching it off forgot the dimming.
5. Click the domain `service`, keep `smash` and send it. A smash works whether the lamp is on or off. Expected:
   `lamp: broken` and `mode = "broken"`. The bulb goes dark, with `lit = false` and `watts = 0`, and the spare bulb
   disappears from the model tree.
6. Send `press` once more. Expected: the switch moves again, but the lamp stays broken. No transition leaves
   `broken`.

Events can wait. A `dim` sent while the lamp is off does nothing at first. If a press turns the lamp on before the dim
is 5 seconds old, the waiting dim then takes effect. Otherwise the dim expires.

### 6. Observe or pull

In the Model pane, turn the Observation switch off. Expected: the status line next to Pull says `Observation off` and
`pulled at revision` with the last revision. Send `press` twice. Expected: the model tree does not change, and
neither do the active states in the Log pane. The simplified log still shows the transitions, because it comes from
the runtime's output. Click Pull. Expected: the tree jumps to the current model,
with two more entries in `presses`, and the revision is higher. Turn observation on again. Expected: the status goes
back to `live at revision`, and changes flow in again.

### 7. Stop

Click Stop in the line of the runs, or press Cmd+F2 / Ctrl+F2. Expected: the label of the run turns `stopped`. The
Model pane says `Observation off` and `final at revision`, and it keeps the last model. The Send button is greyed out.
The first line still says `Docker: running`, because Stop ends the environment and not the runtime. The container keeps
running so the
next run starts fast. Stop runtime in the first line, or Stop Runtime in the Run menu, stops the container and removes
it.

### 8. Change the model and run again

Give the lamp a way back from broken. Add an event at the end of `lamp-events.kmeta`:

```
type "repair" "Repairs a broken lamp" {
}
```

In `lamp.kstates`, add a transition at the end of the `TRANSITIONS` block:

```
TRANSITION "broken" -> "off" {
    CONDITION { EVENT("service", "repair") }
}
```

Press Ctrl+M inside the quotes of a state name, and completion offers the states of the machine. Save with Cmd+S /
Ctrl+S. Then click Run again in the line of the runs, or press Shift+F10. Run would save the files anyway.

Expected: a new run `lamp` appears first in the line of the runs. It starts from the files again, with the lamp off and
both bulbs in place. In the Events pane the domain `service` now offers `smash` and `repair`. Send `smash`, then
`repair`. Expected: the lamp goes from `broken` back to `off`, with `mode = "off"`. The spare bulb stays gone, because
nothing creates it again.

If a file has an error, the run does not reach the runtime. Its label says `blocked`, and the Log pane lists the
error under `Findings that block the run`. Click it to jump to the error.

### 9. Close the IDE

Quit the IDE with Cmd+Q on macOS, or close the window. If a file has unsaved changes, the IDE asks first. Then it stops
every run, stops the runtime container and removes it. This takes at most 15 seconds. If it takes longer than a
second, the window says `Stopping the runtime…`. The Gradle command in the terminal ends with the IDE.

Expected: `docker ps --all --filter label=io.karpfen.ide.runtime=true` lists no container. The image stays, so the
next run starts the runtime in seconds. `docker images --filter label=io.karpfen.ide.runtime=true` shows it as
`karpfen-ide/runtime` with a short hash as its tag. To undo your changes to the example, run
`git restore examples/lamp`.

### 10. Remove the runtime image

To free the disk space, close the IDE and remove the image. The first line removes containers left over after a crash.
When there are none, it prints an error that you can ignore. The next run builds the image again.

```bash
docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)
docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)
```

The IDE only touches Docker resources with the label `io.karpfen.ide.runtime=true`. A build also leaves the untagged
image of its build stage, which `docker images --filter dangling=true` lists, and the base images
`eclipse-temurin:21-jdk` and `debian:trixie-slim`. Remove them with `docker rmi` when nothing else needs them.

## Good to know

The IDE keeps its settings, such as the recent folders, the theme and the runtime choice, in
`~/.karpfen-ide/settings.json`, and its log in `~/.karpfen-ide/logs/ide.log`. The environment variable
`KARPFEN_IDE_HOME` moves both. [archdoc.md](archdoc.md) explains the modules and how they depend on each other.
[devdoc.md](devdoc.md) collects the design decisions and the behaviour to know before you change the code.
