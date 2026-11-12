# Getting started

This guide says what karpfen IDE does and walks through a first run of a small model, a lamp with a wall switch. Plan
about 15 minutes, plus a few minutes once for building the runtime image. Keys are written for macOS first and for
Windows and Linux second, as in Cmd+S / Ctrl+S.

## The idea

karpfen is a small family of modeling languages. A system is a few text files:

| File | What it holds |
|---|---|
| Metamodel, `.kmeta` | Types with properties (`prop`), objects they contain (`has`) and objects they refer to (`knows`). The last type is the root type. |
| Model, `.kmodel` | Objects of these types. Every object has a key, such as `lamp`. |
| State machine, `.kstates` | Behaviour of one object. Entering a state runs actions that change the model. Transitions wait for events and conditions. `EVAL` blocks hold Python. |
| Events, a second `.kmeta` | One type per event. Its properties are the payload. |
| Descriptor, `.kenv.json` | Names the metamodel, the events, the model and each state machine with the object it is attached to. Sets the tick delay and how long events live. |
| Bridge, `.kbridge.json` | Connects the running model to HTTP, MQTT or ROS. |

The karpfen runtime is a server that runs such a system. It works in ticks: in every tick it runs all state machines
once, then it waits for the tick delay. Events come in from outside. An event waits until a state machine takes it or
until its time to live runs out.

karpfen IDE edits these files, checks them while you type, runs a descriptor in a runtime with one command, and shows
the model and the active states while it runs.

## The IDE at a glance

### Editing

- Open a folder. The Project tool window shows its files in three sections: the project file and the descriptors at
  the top, the model files in the middle, the bridge files at the bottom.
- The editor colours the text and underlines errors within a second. The Outline shows the structure of the active
  file. Resting the mouse on a name shows what it is.
- The label `Problems` at the left of the status bar gets a red icon and a number when the folder has problems. A
  click opens the list.
- The karpfen files of one folder are checked as one system. A descriptor says exactly which files belong together.
  Without one, the events must be in `EVENTS.kmeta`, and the metamodel is the other `.kmeta` file.
- To create a descriptor, right-click a folder in the Project tool window and choose New Environment File.

| Action | macOS | Windows and Linux |
|---|---|---|
| Complete Code | Ctrl+M, with the Control key | Ctrl+M, on Windows also Ctrl+Space |
| Go to Definition | Cmd+B or Cmd+click | Ctrl+B or Ctrl+click |
| Find and Replace | Cmd+F and Cmd+R | Ctrl+F and Ctrl+R |
| Go to Line | Cmd+L | Ctrl+G |
| Toggle Comment | Cmd+/ | Ctrl+/ |
| Save and Save All | Cmd+S and Cmd+Option+S | Ctrl+S and Ctrl+Alt+S |
| Run and Stop | Shift+F10 and Cmd+F2 | Shift+F10 and Ctrl+F2 |

There are two zoom controls, each a minus, the size and a plus. `Zoom 100%` at the right of the status bar scales
everything around the editor. `Font size 13` in the bar below the editor scales the editor's text. Neither changes the
other, and a click on the size sets it back.

### Diagrams

| File | Diagram |
|---|---|
| State machine | Statechart: states with their inner states, transitions with their position in the text and their condition. |
| Metamodel | Class diagram: one box per type with its properties. A filled diamond marks `has`, an open arrowhead marks `knows`. |
| Model | Object diagram: one box per object with its key, its type and its values. |

- The buttons Text, Split and Diagram at the left of the bar below the editor choose the view of the active file.
- Drag the empty canvas to move the drawing. The buttons in its corner zoom. A click on a box jumps to it in the text.
- Drag a box to arrange the diagram by hand. A grid of dots appears, and the box moves from grid point to grid point.
- Right-click a line and choose Add waypoint, then drag the waypoint to bend the line. Right-click a waypoint and
  choose Remove waypoint. The waypoints of a line show while the pointer is near the line.
- Drag the label of a line to move it. It keeps its distance to the line.
- Drag a side or a corner of a state with inner states to size it. It never gets smaller than its inner states need.
- The first change by hand fixes every element where it is. The IDE stores the arrangement next to the file, with
  `.vis` appended to its name, as in `lamp.kstates.vis`. After that only new elements are placed automatically.
- Right-click in a statechart to change the state machine. On the empty canvas the menu adds a state or a
  transition. On a state it renames or deletes the state, adds a state inside it, starts a transition, adds entry or
  do actions, makes it the initial state, or moves it out of or into another state. On a transition it deletes the
  transition. Where an entry needs a second state, the states to choose from are marked, and you click one.
- A small circle with a plus marks code that is folded up: the entry and do actions of a state, and the condition
  of a transition. Click it to open the code in a pane over the diagram, edit it and click Save. A transition without
  a condition has an empty one to write into.
- Every such change is an edit of the text, which Undo in the editor takes back.
- The text always wins. What the `.vis` file says about something the text no longer has is dropped. Delete the
  `.vis` file to go back to the automatic layout.
- In the Project tool window the `.vis` file shows below its model file, behind an arrow. It opens as greyed-out text
  that you cannot edit.

### Running

- **In the IDE's Docker container.** This is the default. Each folder gets its own container, which listens only on
  `127.0.0.1`. The first run builds the runtime image, which takes a few minutes once. Closing the folder or the IDE
  stops and removes the container. The image stays. It has Python 3 with numpy, scipy, sympy, pandas, regex,
  python-dateutil and chardet for `EVAL` blocks.
- **Against a runtime at a URL.** Start a karpfen runtime with IDE support yourself, for example with
  `runtime/live-runtime.sh up`. In the first line of the Runtime tool window click URL, enter the URL and click Apply.
  The IDE never stops a runtime it did not start and cannot show its log. Click Docker to go back.

Run saves all open files first, because it uploads the saved files. It picks the descriptor in the active editor, or
the only one of the folder, or asks. A run whose files have errors stops before it reaches the runtime and lists the
errors.

### While a model runs

The Runtime tool window opens with every run. Its first line shows what the runtime is doing. Its second line lists
the runs with the buttons Run again, Stop and Clear finished. Below, three panes show the selected run:

| Pane | What it shows |
|---|---|
| Model | The model as a tree, with Expand all and Collapse all. With observation on, it follows every change. With observation off, Pull reads one snapshot. |
| Events | The form that sends an event: choose a domain, an event and fill in the payload. The payload is checked as you type. Below is the list of the events the environment published. |
| Log | The active states of each state machine, the deployment, the bridges and the log of the runtime. Simplified shows only what the state machines do, such as `lamp  off -> on`, and the errors. |

### Bridges

- A bridge file describes one bridge: its kind, which is `http`, `mqtt` or `ros`, its settings and its routes. An
  inbound route turns a message from outside into an event. An outbound route sends a message whenever a property, the
  active states of a state machine or an event has something new.
- You do not write a bridge file by hand. Configure Bridge in the Run menu, or a click on a bridge file, opens a form.
  Pick lists the events, properties and state machines of the project.
- When the project has bridge files, Run asks which of them to start. The boxes you ticked last time are ticked
  again. The bridges start once the environment runs and stop with it.
- Outbound routes that follow a property or a state machine need observation switched on.

## Walkthrough: the lamp

The folder `examples/lamp` holds a small system. A living room has a lamp and a wall switch.

- A press of the switch turns the lamp on and off. It always comes on at the full power of its bulb, 40 W.
- While it is on, a dimmer turns it up and down in steps of 10 W, between 0 W and the full power.
- Switching it off forgets the dimming.
- A smash breaks the lamp at any time and throws away its spare bulb.
- The switch has its own state machine that moves up and down with each press.
- The descriptor sets a tick delay of 500 ms, so you can watch each change, and lets an event wait up to 5 seconds.

### 1. What you need

- A JDK 21. Gradle also runs on JDK 17 or newer and downloads a JDK 21 if none is installed.
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

On Windows, run `gradlew.bat :app:ide:run --args="%CD%\examples\lamp"`. The path must be absolute. The first build
takes a few minutes, and the command keeps running while the IDE is open.

Expected: the window opens on the folder. The Project tool window lists the descriptor `lamp.kenv.json` at the top,
five model files in the middle and three bridge files at the bottom. The status bar shows `Problems` without an icon
and `Runtime: stopped`.

### 3. Look around

1. Double-click `lamp.kstates`. Expected: it opens with coloured keywords, and the Outline lists its states and
   transitions.
2. Click Split in the bar below the editor. Expected: the statechart appears next to the text. It starts at the
   initial state `off`, with `on` and its three inner states below. A transition reads like `1: switch/press`.
3. Click a state in the diagram. Expected: the editor jumps to it.
4. Drag the state `broken`. Expected: a grid of dots appears, and the state moves on it. In the Project tool window
   `lamp.kstates` gets an arrow, with `lamp.kstates.vis` behind it.
5. Open `lamp.kmeta` and click Split. Expected: a class diagram. A line with a diamond leads from `Lamp` to `Bulb`,
   one with an arrowhead from `Lamp` to `Switch`.
6. Open `lamp.kmodel` and click Split. Expected: an object diagram. The box `bulb : Bulb` shows `maxWatts = 40`.
7. In `lamp.kstates`, Cmd+click / Ctrl+click on `Lamp` in `STATEMACHINE ATTACHED TO "Lamp"`. Expected: `lamp.kmeta`
   opens at the type.
8. Open `lamp.kenv.json` and rest the mouse on the key `tickDelayMs`. Expected: a tooltip explains the key.
9. In `lamp.kstates`, delete the `{` after `STATE "broken"`. Expected: a red underline appears, and the status bar
   shows `Problems: 1` with a red icon. Undo with Cmd+Z / Ctrl+Z. Expected: everything is clean again.

### 4. Run it

1. Choose Run in the Run menu, or press Shift+F10. On a Mac keyboard you may have to hold Fn.
2. The dialog Bridges asks which bridges to start. Leave the boxes unticked and click Run.

Expected:

- The Runtime tool window opens at the bottom and takes three quarters of the window's height.
- Its second line shows a run named `lamp`, whose label moves from `preparing` to `running`.
- On the first run its first line shows `Docker: building the runtime image` for a few minutes, and at last
  `Docker: running at 127.0.0.1:` with a port.
- The Log pane starts with `lamp: off` and `wallSwitch: down` and the line `Deployment done`. A click on that line
  shows the steps.
- The Model pane says `Observation on`. Click Expand all. The lamp shows `mode = "off"`, the bulb `maxWatts = 40`,
  `watts = 0` and `lit = false`, and the switch `position = "down"`.

If Docker is not running, the run turns `failed` and the first line names the problem in red. Start Docker and run
again.

### 5. Play with it

The form in the Events pane starts on the domain `service`. After each Send, look at the Model pane and the Log pane.

| Step | Do this | Expected |
|---|---|---|
| 1 | Click the domain `switch` and `Send press`. | The lamp goes on at full power: `lamp: on > full`, `mode = "on"`, `lit = true`, `watts = 40`. The switch is `up`. |
| 2 | Click the event `dim`. Replace `false` in the payload by `3`. | The form shows an error, because `bright` is a boolean, and the button is greyed out. |
| 3 | Write `false` again and click `Send dim`. | The log shows `lamp  on -> dimmer`, and the bulb has `watts = 30`. |
| 4 | Send it three more times, then once more. | The bulb goes down to `watts = 0`. The last send changes nothing. |
| 5 | Change the payload to `"bright": true` and send it. | `lamp  on -> brighter`, `watts = 10`. Each further send adds 10 W, up to 40 W. |
| 6 | Dim a step, then send `press` twice. | The lamp goes off with `watts = 0`, then comes on at `watts = 40` again. |
| 7 | Click the domain `service` and send `smash`. | `lamp: broken`, `mode = "broken"`, `lit = false`, `watts = 0`. The spare bulb disappears from the tree. |
| 8 | Send `press` once more. | The switch moves, but the lamp stays broken. |

Events can wait. A `dim` sent while the lamp is off takes effect if a press turns the lamp on within 5 seconds.
Otherwise it expires.

### 6. Observe or pull

1. In the Model pane, turn the Observation switch off. Expected: the status says `Observation off`.
2. Send `press` twice. Expected: the tree and the active states do not change. The simplified log still shows the
   transitions.
3. Click Pull. Expected: the tree jumps to the current model, with two more entries in `presses`.
4. Turn observation on again. Expected: the status goes back to `live at revision`.

### 7. Stop

Click Stop in the line of the runs, or press Cmd+F2 / Ctrl+F2. Expected: the run turns `stopped`, the Model pane keeps
the last model, and the Send button is greyed out. The container keeps running, so the next run starts fast. Stop
runtime in the first line stops and removes it.

### 8. Change the model and run again

Give the lamp a way back from broken.

1. Add an event at the end of `lamp-events.kmeta`:

   ```
   type "repair" "Repairs a broken lamp" {
   }
   ```

2. In `lamp.kstates`, add a transition at the end of the `TRANSITIONS` block. Ctrl+M inside the quotes of a state
   name offers the states.

   ```
   TRANSITION "broken" -> "off" {
       CONDITION { EVENT("service", "repair") }
   }
   ```

3. Click Run again in the line of the runs, and Run in the dialog Bridges. Run saves the files.
4. Send `smash`, then `repair` in the domain `service`. Expected: the lamp goes from `broken` back to `off`. The
   spare bulb stays gone, because nothing creates it again.

If a file has an error, the run is `blocked`, and the Log pane lists the error under `Findings that block the run`.

### 9. Try a bridge

1. Run again, tick `http.kbridge.json` in the dialog Bridges and click Run. Expected: the Log pane lists the bridge
   below the deployment.
2. In a terminal, press the wall switch and make the lamp dimmer:

   ```
   curl -X POST http://127.0.0.1:8090/press
   curl -X POST http://127.0.0.1:8090/dim -d '{"bright": false}'
   ```

   Expected: the lamp goes on, then down to `watts = 30`, as if you had sent the events in the form.

The other two bridge files need a partner. Without it the bridge shows as failed, and the run goes on.

| File | Needs | Does |
|---|---|---|
| `mqtt.kbridge.json` | An MQTT broker on `localhost:1883`. | Takes presses on `home/lamp/press`. Publishes the power on `home/lamp/watts` and the active states on `home/lamp/state`. |
| `ros.kbridge.json` | A rosbridge server on `localhost:9090`, the ROS package `rosbridge_suite`. | Takes presses on `/lamp/press` and dim steps on `/lamp/dim`. Publishes the power on `/lamp/watts` and the active states on `/lamp/state`. For ROS 1, tick Legacy ROS 1 system in its form. |

### 10. Close the IDE and clean up

Quit with Cmd+Q on macOS, or close the window. The IDE asks about unsaved changes, stops every run and removes the
runtime container. This takes at most 15 seconds. To undo your changes to the example, run
`git restore examples/lamp` and delete the `.vis` files you made.

The image stays, so the next run starts in seconds. To free the disk space, remove it. The first line removes
containers left over after a crash and prints an error when there are none:

```bash
docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)
docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)
```

## Good to know

Each project keeps its settings in the file `.karpfenproject` in its folder: the theme, the two zoom sizes, the tool
windows, the runtime choice, the open files and the bridges of the last run. Open the folder again, on this machine or
another, and the IDE looks the way you left it. The file is plain text, so you can read it, edit it and commit it:

```
theme = dark
zoom = 110
editorFontSize = 14
pane.runtime = shown 400
runtime.host = docker
open = lamp.kstates
active = lamp.kstates
```

Only the recent folders and the place of the window stay outside the project, in `~/.karpfen-ide/settings.json`. The
log is in `~/.karpfen-ide/logs/ide.log`. The environment variable `KARPFEN_IDE_HOME` moves both.

[archdoc.md](archdoc.md) explains the modules, [devdoc.md](devdoc.md) the design decisions and the behaviour to know
before you change the code, and [todo.md](todo.md) what is open.
