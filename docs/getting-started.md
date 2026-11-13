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
- The editor colours the text and underlines errors within a second. The Outline shows what the active file is
  made of: the types of a metamodel, the objects of a model, the states and transitions of a state machine. Resting the mouse on a name shows what it is.
- The label `Problems` at the left of the status bar gets a red icon and a number when the folder has problems. A
  click opens the list. It has two groups. Local holds what the editor finds while you type. Global holds what only
  a validation of the whole project finds, see [Validating](#validating). A double-click on a problem leads to its
  place.
- The karpfen files of one folder are checked as one system. A descriptor says exactly which files belong together.
  Without one, the events must be in `EVENTS.kmeta`, and the metamodel is the other `.kmeta` file.
- To create a descriptor, right-click a folder in the Project tool window and choose New Environment File.

### Validating

The editor checks each file while you type. Validate checks the project as a whole, which takes longer and therefore
only happens when you ask for it.

| To | Do this |
|---|---|
| Validate the project | Click Validate in the bar below the editor, or choose Run > Validate Project. |
| Validate from a terminal | Run `karpfen-ide validate <folder>`, see [Without a window](#without-a-window). |

- The answer is a dialog with a summary, such as `2 errors and 1 warning` or `No problems found.`, and a list of the
  findings, each with its file, its line and what is wrong. Errors come first. Close is its only button.
- A validation checks what the editor already found, and on top of that what only shows across files: whether the
  paths in the actions and conditions of a state machine exist on the type it is attached to, whether an operation
  fits what its path leads to, whether the `$(...)` references in `EVAL` code resolve, whether the macros and events
  fit their use, and whether every state can be reached.
- Every run validates the project first. An error anywhere in the project blocks the run, and the dialog says so in
  its title. Warnings do not block a run and open no dialog.
- After the dialog is closed the findings stay in the Problems list. What only a validation finds stands in the group
  Global, which reads `Global (not validated yet)` before the first validation. Validate, Run > Validate Project and
  every run fill it anew. It keeps its findings while you edit, until the next validation. These findings are not
  underlined in the editor.
- A state machine file that no descriptor uses is checked on its own as well, with the language features of the
  project. An error in it blocks a run like any other. A model or metamodel file that no descriptor uses is checked
  for its syntax only.
- These checks come from dsl-tools. Its guide `guides/verification.md` lists every check with its code.

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
  choose Remove waypoint. The waypoints of a line show while the pointer is near the line. Each line between a state
  and the bar of a split or join is bent on its own.
- Drag the label of a line to move it. It keeps its distance to the line.
- Drag a side or a corner of a state with inner states to size it. It never gets smaller than its inner states need.
- The first change by hand fixes every element where it is. The IDE stores the arrangement next to the file, with
  `.vis` appended to its name, as in `lamp.kstates.vis`. After that only new elements are placed automatically.
- Right-click in a statechart to change the state machine. On the empty canvas the menu adds a state, a transition,
  a split or a join, and opens the macros. On a state it renames or deletes the state, adds a state inside it, starts
  a transition, adds entry or do actions, makes it the initial state, or moves it out of or into another state. On a
  transition it changes the priority, sets the mark not looping, or deletes the transition. Where an entry needs
  another state, the states to choose from are marked, and you click one.
- A timeout of a state is a dashed line to the state it leads to, with a caption such as `after 10 ticks`. Its menu
  edits the tick limit as text, leads the timeout to another state that you then click, or removes it.
- A state can remember where it was left, limit its ticks and halt a debugger. A small circle with `H` marks a
  shallow history, one with `H*` a deep history. A red dot on the left border of a state is a breakpoint at its
  entry, one on the right border a breakpoint at its exit. A red dot in front of the caption of a transition is a
  breakpoint at its start, one behind it a breakpoint at its end. The menu of a state sets and removes its history
  and its breakpoints and adds a tick limit. The menu of a transition sets and removes its breakpoints.
- Edit as text, on a state, a transition, a type, an object or a relation, opens that piece of the text in a pane
  over the diagram. So whatever the menu has no entry for can still be done there.
- Right-click in a class diagram to add a type, and on a type to rename or delete it, to add a property, or to add
  a relation to a type you then click. In an object diagram, right-click an object to rename or delete it and to add
  a property, an object it has, or a reference to an object you then click. A right-click on a row edits or deletes
  the statement behind it, and one on a line renames or deletes the relation.
- A rename works in both directions within one file. Rename in the diagram and the text follows. Rename a state,
  a type or an object in the text and it keeps its place in the diagram. Other files that use the name are not
  changed. Update them by hand.
- A small circle with a plus marks code that is folded up: the tick limit and the entry and do actions of a state,
  and the condition of a transition. Click it to open the code in a pane over the diagram, edit it and click Save. Drag the head of the pane to move
  it, and a side or a corner to size it. A transition without
  a condition has an empty one to write into. A tick limit whose code you delete is taken out of its state.
- Every such change is an edit of the text, which Undo in the editor takes back.
- The text always wins. What the `.vis` file says about something the text no longer has is dropped. Delete the
  `.vis` file to go back to the automatic layout.
- While a model runs, the statechart of a state machine marks the states the machine is in. It follows the run that
  is selected in the Runtime tool window, as long as observation is on.
- In the Project tool window the `.vis` file shows below its model file, behind an arrow. It opens as greyed-out text
  that you cannot edit.

### Running

A model runs in the IDE's own Docker container.

- Each folder gets its own container, which listens only on `127.0.0.1`.
- The first run builds the runtime image, which takes a few minutes once.
- Closing the folder or the IDE stops and removes the container. The image stays.
- The image has Python 3 with numpy, scipy, sympy, pandas, regex, python-dateutil and chardet for `EVAL` blocks.
- The IDE runs models nowhere else. To run a model on a runtime of your own, use the karpfen-runtime application
  without the IDE.

Run saves all open files first, because it uploads the saved files. It picks the descriptor in the active editor, or
the only one of the folder, or asks. Then it validates the whole project, as [Validating](#validating) describes. A
project with an error does not run.

A run of a project without errors goes on with the dialog Run.

| In the dialog | Effect |
|---|---|
| A box per bridge file, if the project has any | The ticked bridges start with the run. |
| Capture run to file | The run writes the whole log of the runtime into `karpfen-runs/` in the project, in a file named after the date and time, such as `2026-11-13_14-32-05.log`. |

- The boxes you ticked last time are ticked again.
- A captured file starts with a few lines about the run. Then it holds every line of the runtime's log from the
  deployment to the end of the run, also the lines of each tick that the Log pane leaves out.
- The folder `karpfen-runs` does not show in the Project tool window. The Runtime tool window names the file of the
  selected run.

Five parts of the state machine language are optional features that each project switches on by hand: histories,
tick limits, breakpoints, macros, and splits and joins. All of them are off in a new project.

| To | Do this |
|---|---|
| Switch a feature on or off | Choose Run > Language Features and click its entry. The entry says what the click does, such as `Switch On Histories`. |
| See what is on | Open `.karpfenproject`. Each feature that is on has a line such as `feature.history = on`. |

- While a feature is off, every use of it in a state machine is an error, and a run is blocked. Completion and the
  menu of a statechart do not offer it.
- A run switches on the runtime features of what the project has on: History for histories, TickLimit for tick
  limits and Breakpoint for breakpoints. The deployment steps of the run show a line such as
  `Switching on History, TickLimit`.
- With breakpoints on, a run has a debugger. See [Debugging](#debugging).

### While a model runs

The Runtime tool window opens with every run. Its first line shows what the runtime is doing. Its second line lists
the runs with the buttons Run again, Stop and Clear finished. Below, three panes show the selected run:

| Pane | What it shows |
|---|---|
| Model | The model as a tree, with Expand all and Collapse all. With observation on, it follows every change. With observation off, Pull reads one snapshot. |
| Events | The form that sends an event: choose a domain, an event and fill in the payload. The payload is checked as you type. Below is the list of the events the environment published. |
| Log | The active states of each state machine, the deployment, the bridges and the log of the runtime. Simplified shows only what the state machines do, such as `lamp  off -> on`, and the errors. |

### Debugging

A run halts at the breakpoints of its state machines once breakpoints are switched on for the project.

| Step | What to do |
|---|---|
| 1 | Choose Run > Language Features > Switch On Breakpoints. |
| 2 | Write `BREAKPOINT { ENTRY }` or `{ EXIT }` into a state, or `BREAKPOINT { START }` or `{ END }` into a transition. In a statechart, the menu of a state or a transition does the same. |
| 3 | Run. The Runtime tool window shows a line Debugger below the list of runs. |

| The Debugger line says | It offers |
|---|---|
| `Running` | Pause, which lets the engine finish its tick and then wait. |
| `Paused before the entry of cars wait`, `Paused before the transition cars wait to cars go` and the like | Resume (F9) and Step (F8). |
| `Paused between two ticks` | Resume (F9) and Step (F8). |

- Resume lets the run go on to the next breakpoint.
- Step goes on to the next breakpoint or to the end of the tick, whichever comes first. From between two ticks it
  runs one tick.
- The open statechart frames the state or the transition the run is paused at with a dashed line.
- While a run is paused, the model shows what the tick has done so far. The active states are those of the last
  finished tick, because the runtime tells a change of the states when its tick ends.
- Pause, Resume and Step are also in the Run menu. The journal of the run notes every pause and every resume.

### Bridges

- A bridge file describes one bridge: its kind, which is `http`, `mqtt` or `ros`, its settings and its routes. An
  inbound route turns a message from outside into an event. An outbound route sends a message whenever a property, the
  active states of a state machine or an event has something new.
- You do not write a bridge file by hand. Configure Bridge in the Run menu, or a click on a bridge file, opens a form.
  Pick lists the events, properties and state machines of the project.
- When the project has bridge files, the dialog Run lists them, and you tick the ones to start. The bridges start
  once the environment runs and stop with it.
- Outbound routes that follow a property or a state machine need observation switched on.

## Without a window

The IDE also works from a terminal, without a window. It validates a project, and it runs a project with its bridges
until you stop it. A run without a window has no debugger.

### The commands

| Command | What it does |
|---|---|
| `karpfen-ide <folder>` | Opens the window on the folder, as always. |
| `karpfen-ide validate <folder> [--format text\|json]` | Validates the whole project, prints the findings and ends. |
| `karpfen-ide run <folder> [options]` | Validates the project, runs it and prints what happens until it is stopped. |
| `karpfen-ide validate --help`, `karpfen-ide run --help` | Prints the usage. |

A folder that is itself named `validate` or `run` must be written with a path in front, as in `./run`, when it is the
first argument.

### Starting it

| From | Command |
|---|---|
| The sources | `./gradlew :app:ide:run --args="validate /absolute/path/to/project"` |
| The application on macOS | `karpfen-ide.app/Contents/MacOS/karpfen-ide validate <folder>` |
| The application on Linux | `karpfen-ide/bin/karpfen-ide validate <folder>` |
| The application on Windows | `karpfen-ide\karpfen-ide.exe validate <folder>` |

From the sources the folder must be an absolute path. `./gradlew :app:ide:createDistributable` builds the application
into `app/ide/build/compose/binaries/main/app/`.

On Windows the application is built with a console, so the commands have somewhere to print. Opening the window by a
double-click therefore also opens a console window.

### Validating

```bash
karpfen-ide validate examples/lamp
```

It prints one line per finding, errors first, and then a summary. The checks are those of Validate in the window.

```
lamp.kstates:13: error: mismatched input 'STATMACHINE' expecting 'STATEMACHINE'
switch.kstates:3: warning: This EVAL has no return, so it yields no value; return the value it computes
1 error and 1 warning
```

A project without findings prints `No problems found.`

With `--format json` it prints one JSON document instead, for a program to read.

```bash
karpfen-ide validate examples/lamp --format json
```

```json
{
    "root": "/home/me/karpfen-IDE/examples/lamp",
    "errorCount": 1,
    "warningCount": 0,
    "problems": [
        {
            "path": "lamp.kstates",
            "line": 13,
            "column": 1,
            "severity": "error",
            "message": "mismatched input 'STATMACHINE' expecting 'STATEMACHINE'",
            "code": "syntax-error"
        }
    ]
}
```

| Member | Meaning |
|---|---|
| `root` | The folder of the project, as an absolute path. |
| `errorCount`, `warningCount` | How many errors and warnings the project has. |
| `problems` | The findings, sorted by path, line and column. |
| `path` | The file, relative to the folder. |
| `line`, `column` | Where the finding starts. Both count from 1. |
| `severity` | `error`, `warning`, `info` or `hint`. |
| `message` | What is wrong, in words. |
| `code` | The stable name of the kind of finding. |

The exit status is the same in both formats.

### Running

```bash
karpfen-ide run examples/lamp --descriptor lamp.kenv.json --bridge http.kbridge.json --capture
```

| Option | Meaning |
|---|---|
| `--descriptor <file>` | The descriptor to run, relative to the folder or absolute. It can be left out when the project has exactly one. |
| `--bridge <file>` | A bridge file of the project to start with the run. Give the option once per bridge. Without it no bridge starts. |
| `--capture` | Writes the log of the run to a file in `karpfen-runs/`, as the box in the window's run dialog does. |
| `--verbose` | Also prints every line of the runtime's log. |

The options may stand before or after the folder. A value follows its option as the next argument or after `=`.

What a run does, in this order:

1. It validates the whole project. With an error it prints the findings as `validate` does and does not run. With
   warnings only it prints them the same way, then `The run goes on despite the warnings.`, and runs.
2. It starts the runtime in the IDE's Docker container. The first run builds the runtime image.
3. It deploys the descriptor and starts the bridges.
4. It prints what happens until you stop it with Ctrl+C or a terminate signal.
5. It stops the bridges and the environment, removes the runtime container and ends.

This is what a run of the lamp with one bridge prints. The switch is pressed once through the bridge, and then the
run is stopped. The first run prints a line for each step of building the image as well.

```
Running lamp.kenv.json with http.kbridge.json
Docker: not started
Run lamp: preparing
Run lamp: waiting for the runtime
Docker: checking the Docker engine
Docker: creating the container
Docker: starting the container
Docker: waiting for the runtime
Docker: running at 127.0.0.1:65457
Run lamp: deploying
http.kbridge.json: not running
Checking the runtime at http://127.0.0.1:65457: done, The runtime is up and supports the IDE
Creating the environment: done, env-1794579423685
Connecting to the environment: done, Connected as karpfen-ide-254666e10161
Uploading lamp.kmeta: done
Uploading lamp-events.kmeta: done
Uploading lamp.kmodel: done
Attaching lamp.kstates to lamp: done
Attaching switch.kstates to wallSwitch: done
Setting the tick delay to 500 ms: done
Setting the event time to live to 5000 ms: done
Listening to 2 event domains: done, service, switch
Switching on runtime features: skipped, The project switches on no language feature that needs one
Activating the environment: done
Switching observation on: done, The runtime read the model as analyzed
Starting the environment: done
14:17:03.888  lamp  starts in off
14:17:03.890  wallSwitch  starts in down
14:17:03.892  engine  Engine started with 2 state machine(s), tickDelay=500ms
14:17:03.895  lamp  runs the entry actions of off
lamp: off
wallSwitch: down
Confirming the start: done, Every state machine reported its state
Run lamp: running
http.kbridge.json: starting
http.kbridge.json: running, 0 in, 0 out
14:17:04.896  lamp  off -> full
14:17:04.898  wallSwitch  down -> up
lamp: on > full
wallSwitch: up
14:17:05.397  lamp  runs the entry actions of on
14:17:05.399  lamp  runs the entry actions of full
14:17:05.414  wallSwitch  runs the entry actions of up
Stop requested.
Shutting down the run and its bridges, then the runtime.
Run lamp: stopped
http.kbridge.json: not running
14:17:06.405  engine  Engine stopped after 5 ticks
Docker: not started
Shut down.
```

| Line | Meaning |
|---|---|
| `Running <descriptor> with <bridges>` | What the run was asked to do. `captured to a file` is added with `--capture`. |
| `Docker: ...` | What the runtime container is doing, in the words of the Runtime tool window. |
| `Run <name>: <phase>` | The phase of the run: preparing, waiting for the runtime, deploying, running, stopped. |
| `<step>: done`, `skipped` or `failed`, with a detail | A step of the deployment. |
| `<object>: <states>` | The active states of the state machine on that object, whenever the runtime reports new ones. |
| `<bridge file>: ...` | The state of a bridge, and once it runs how many messages it took in and sent out. |
| `<time>  <object>  <text>` | A line of the simplified log, as the Log pane shows it. |
| `[stdout] ...`, `[stderr] ...` | A line of the runtime's log, with `--verbose`. |
| `Captured to karpfen-runs/<file>` | Where the run is captured. |
| `Stop requested.`, `Shutting down ...`, `Shut down.` | The end of the run. What the bridges and the runtime say while they stop stands in between. |

A run that does not get going says why and ends: `The run does not start: the project has errors.`,
`The run does not start: the deployment is blocked.`, `The run failed.` or `The run ended on its own.`

### What a run reads from the project

A run without a window takes one thing from `.karpfenproject`: which optional language features are on. Everything
else in that file, such as the bridges ticked in the window, is ignored. Nothing is written, neither to
`.karpfenproject` nor to the settings of the user.

Breakpoints never halt a run without a window, also when the project has them switched on.

### Exit statuses

| Status | Meaning |
|---|---|
| 0 | The validation found no error. Warnings are allowed. |
| 1 | The project has errors. A run did not start. |
| 2 | The command line was wrong. A message and the usage are printed. |
| 3 | A run could not start, failed or ended on its own, or the folder could not be opened. |

A run that is stopped by a signal ends with the status the system gives a process that got that signal.

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
2. The dialog Run lists the bridges and the box Capture run to file. Leave the boxes unticked and click Run.

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

1. Run again, tick `http.kbridge.json` in the dialog Run and click Run. Expected: the Log pane lists the bridge
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

The container is also removed when the IDE is stopped from outside, for example with Ctrl+C on the Gradle command.
Only a process that is killed outright leaves it behind.

The image stays, so the next run starts in seconds. To free the disk space, remove it. The first line removes
containers left over after such a kill and prints an error when there are none:

```bash
docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)
docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)
```

## Good to know

Each project keeps its settings in the file `.karpfenproject` in its folder: the theme, the two zoom sizes, the tool
windows, the language features, the open files and the bridges of the last run. Open the folder again, on this machine or
another, and the IDE looks the way you left it. The file is plain text, so you can read it, edit it and commit it:

```
theme = dark
zoom = 110
editorFontSize = 14
pane.runtime = shown 400
feature.history = on
open = lamp.kstates
active = lamp.kstates
```

Only the recent folders and the place of the window stay outside the project, in `~/.karpfen-ide/settings.json`. The
log is in `~/.karpfen-ide/logs/ide.log`. The environment variable `KARPFEN_IDE_HOME` moves both.

[archdoc.md](archdoc.md) explains the modules, [devdoc.md](devdoc.md) the design decisions and the behaviour to know
before you change the code, and [todo.md](todo.md) what is open.
