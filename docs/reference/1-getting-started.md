# 1 Getting started

karpfen describes a system as a few text files and runs it. The example of this chapter is a lamp with a wall switch:
you open it in the IDE, run it, send events to it and connect it to HTTP. Keys are written for macOS first and for Windows and Linux second, as in
Cmd+S / Ctrl+S.

## 1.1 What karpfen is

karpfen has three parts.

| Part | What it is | Reference |
|---|---|---|
| The modeling languages | KMeta for the types of a domain, KModel for the objects, KStates for the state machines that change the objects. | [2 Modeling languages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2-modeling-languages.md) |
| The runtime | A server that runs a model with its state machines in ticks. Events come in from outside, and clients watch the model change. | [3 Execution](https://github.com/karpfenproject/karpfen-runtime/blob/main/docs/reference/3-execution.md) |
| The IDE | A desktop program that edits the files, checks them while you type, draws them as diagrams, runs them in its own runtime and shows the run. | [4 The IDE](4-ide.md) |

A system in karpfen is a folder of these files.

| File | What it holds |
|---|---|
| Metamodel, `.kmeta` | The types of the domain with their properties and relations. |
| Event metamodel, a second `.kmeta` | One type per event. The properties of a type are the payload of its event. |
| Model, `.kmodel` | The objects of the domain, each with a key such as `lamp`. |
| State machine, `.kstates` | The behaviour of one object. Entering a state runs actions that change the model. Transitions wait for events and conditions. |
| Environment descriptor, `.kenv.json` | Which metamodel, events, model and state machines run together, and which object each state machine is attached to. |
| Bridge, `.kbridge.json` | A connection of the running model to HTTP, MQTT or ROS. |

The descriptor and the bridge files belong to the IDE. [4.1 Projects](4.1-projects.md) describes them.

## 1.2 What you need

- Docker Desktop or another local Docker engine, running. The IDE runs every model in a Docker container of its own.
- The repository karpfen-IDE. It holds the lamp example in `examples/lamp`.
- To start the IDE from the sources: the submodules of the repository and a JDK 21. Gradle also runs on JDK 17 or
  newer and downloads a JDK 21 if none is installed. An installed IDE brings its own Java and needs neither.

```bash
git clone --recurse-submodules https://github.com/karpfenproject/karpfen-IDE.git
cd karpfen-IDE
```

In a clone without the submodules, run `git submodule update --init --recursive`.

## 1.3 Starting the IDE

The IDE starts from an installer or from the sources.

### 1.3.1 From an installer

The [releases of karpfen-IDE](https://github.com/karpfenproject/karpfen-IDE/releases) hold one installer per system.
GitHub builds them from a release tag.

| System | File | Installing | Program |
|---|---|---|---|
| macOS, Apple silicon | `karpfen-ide-<version>-macos-arm64.dmg` | Open the file and drag `karpfen-ide` to Applications. | `/Applications/karpfen-ide.app` |
| macOS, Intel | `karpfen-ide-<version>-macos-x64.dmg` | Open the file and drag `karpfen-ide` to Applications. | `/Applications/karpfen-ide.app` |
| Windows | `karpfen-ide-<version>-windows-x64.msi` | Open the file. | `karpfen-ide` in the Start menu, or `C:\Program Files\karpfen-ide\karpfen-ide.exe` |
| Debian and Ubuntu | `karpfen-ide-<version>-linux-x64.deb` | `sudo apt install ./karpfen-ide-<version>-linux-x64.deb` | `/opt/karpfen-ide/bin/karpfen-ide` |

The installers are not signed. macOS and Windows stop an unsigned program, and you have to allow it by hand once.

On macOS the first start fails with a message that macOS could not verify the app. Open System Settings, go to
Privacy & Security and click Open Anyway next to the entry for `karpfen-ide`. Then start the app again. If macOS says
that the app is damaged, it shows no such entry. Remove the mark of the download in a terminal and start the app
again:

```bash
xattr -dr com.apple.quarantine /Applications/karpfen-ide.app
```

On Windows the installer stops at the message "Windows protected your PC". Click More info and then Run anyway.

On Debian and Ubuntu nothing has to be allowed.

Start the program and open the folder `examples/lamp` of the repository with Open Folder.

### 1.3.2 From the sources

From the root of the repository, on macOS and Linux:

```bash
./gradlew :app:ide:run --args="$PWD/examples/lamp"
```

On Windows:

```text
gradlew.bat :app:ide:run --args="%CD%\examples\lamp"
```

The path of the folder must be absolute. Without a folder the IDE opens on its welcome view, with Open Folder and the
recent folders. The first build takes a few minutes. The command keeps running while the IDE is open.

To build the application as a program of its own, run `./gradlew :app:ide:createDistributable`. It lands in
`app/ide/build/compose/binaries/main/app/`. [4.8 Command line](4.8-command-line.md) names the program file on each
system.

## 1.4 The lamp example

The folder `examples/lamp` holds a small system. A living room has a lamp and a wall switch.

- A press of the switch turns the lamp on and off. The lamp always comes on at the full power of its bulb, 40 W.
- While the lamp is on, a dimmer turns it up and down in steps of 10 W, between 0 W and the full power.
- Switching the lamp off forgets the dimming.
- A smash breaks the lamp at any time and throws away its spare bulb.
- The switch has its own state machine that moves up and down with each press.
- The descriptor sets a tick delay of 500 ms, so each change can be watched, and lets an event wait up to 5 seconds.

| File | Content |
|---|---|
| `lamp.kmeta` | The types `Bulb`, `Switch`, `Lamp` and `Room`. |
| `lamp-events.kmeta` | The events `press`, `dim` with the payload `bright`, and `smash`. |
| `lamp.kmodel` | The room `living room` with the lamp `lamp`, its bulbs `bulb` and `spare`, and the switch `wallSwitch`. |
| `lamp.kstates` | The state machine of the lamp, with the states `off`, `on` with `full`, `brighter` and `dimmer` inside, and `broken`. |
| `switch.kstates` | The state machine of the switch, with the states `down`, `up` and `back down`. |
| `lamp.kenv.json` | The descriptor. It attaches `lamp.kstates` to `lamp` and `switch.kstates` to `wallSwitch`. |
| `http.kbridge.json`, `mqtt.kbridge.json`, `ros.kbridge.json` | One bridge of each kind. |

## 1.5 Looking around

After the start of [1.3 Starting the IDE](#13-starting-the-ide) the window shows the folder. The Project tool window
lists the descriptor `lamp.kenv.json` at the top, the five model files in the middle and the three bridge files at the
bottom. The status bar shows `Problems` without an icon and `Runtime: stopped`.

1. Double-click `lamp.kstates`. The file opens with coloured keywords, and the Outline lists its states and
   transitions.
2. Click Split in the bar below the editor. The statechart appears next to the text. It starts at the initial state
   `off`, with `on` and its three inner states. A transition caption reads like `1: switch/press`.
3. Click a state in the diagram. The editor selects its name in the text.
4. Drag the state `broken`. A grid of dots appears, and the state moves on it. In the Project tool window
   `lamp.kstates` can now be expanded and shows `lamp.kstates.vis` below it.
5. Open `lamp.kmeta` and click Split. A class diagram appears. A line with a filled diamond at `Lamp` leads to `Bulb`,
   and a line with an open arrowhead leads from `Lamp` to `Switch`.
6. Open `lamp.kmodel` and click Split. An object diagram appears. The box `bulb : Bulb` shows `maxWatts = 40`.
7. In `lamp.kstates`, Cmd+click / Ctrl+click on `Lamp` in `STATEMACHINE ATTACHED TO "Lamp"`. `lamp.kmeta` opens at
   the type.
8. Open `lamp.kenv.json` and rest the mouse on the key `tickDelayMs`. A tooltip explains the key.
9. In `lamp.kstates`, delete the `{` after `STATE "broken"`. A red wavy underline appears, and the status bar shows
   `Problems:` with a number and an error icon. Undo with Cmd+Z / Ctrl+Z, and the file is clean again.

## 1.6 Running the lamp

1. Choose Run > Run, or press Shift+F10. On a Mac keyboard you may have to hold Fn.
2. The dialog Run lists the three bridge files and the box Capture run to file. Leave the boxes unticked and click
   Run.

The Runtime tool window opens at the bottom and takes three quarters of the window's height. Its second line shows a
run named `lamp`, whose label moves from `preparing` to `running`. On the first run its first line reads
`Docker: building the runtime image` for a few minutes, and then `Docker: running at 127.0.0.1:` with a port.

The Log pane starts with `lamp: off`, `wallSwitch: down` and the line `Deployment done` with the number of steps. A
click on that line shows the steps. The Model pane reads `Observation on`. Click Expand all. The lamp shows
`mode = "off"`, the bulb `maxWatts = 40`, `watts = 0` and `lit = false`, and the switch `position = "down"`.

If Docker is not running, the run turns `failed`, and the first line of the Runtime tool window names the problem in
red. Start Docker and click Run again.

## 1.7 Sending events

The Events pane sends events into the run. Choose a domain under 1. Domain, an event under 2. Event, and click the
Send button. After each send, look at the Model pane and the Log pane.

| Step | Do this | What happens |
|---|---|---|
| 1 | Click the domain `switch`, the event `press`, and `Send press`. | The lamp goes on at full power: `lamp: on > full`, `mode = "on"`, `lit = true`, `watts = 40`. The switch is `up`. |
| 2 | Click the event `dim`. Replace `false` in the payload by `3`. | The form shows an error, because `bright` is a boolean, and the Send button is greyed out. |
| 3 | Write `false` again and click `Send dim`. | The log shows `lamp  on -> dimmer`, and the bulb has `watts = 30`. |
| 4 | Send it three more times, then once more. | The bulb goes down to `watts = 0`. The last send changes nothing. |
| 5 | Change the payload to `"bright": true` and send it. | `lamp  on -> brighter`, `watts = 10`. Each further send adds 10 W, up to 40 W. |
| 6 | Dim a step, then send `press` twice. | The lamp goes off with `watts = 0`, then comes on at `watts = 40` again. |
| 7 | Click the domain `service` and send `smash`. | `lamp: broken`, `mode = "broken"`, `lit = false`, `watts = 0`. The spare bulb disappears from the tree. |
| 8 | Send `press` once more. | The switch moves, but the lamp stays broken. |

An event waits for a state machine that takes it. A `dim` sent while the lamp is off takes effect if a press turns the
lamp on within 5 seconds. Otherwise it expires.

## 1.8 Observing and pulling

1. In the Model pane, turn the Observation switch off. The status reads `Observation off`.
2. Send `press` twice. The tree and the active states stay as they are. The simplified log still shows the
   transitions.
3. Click Pull. The tree shows the current model, with two more entries in `presses`.
4. Turn observation on again. The status reads `live at revision` with a number.

## 1.9 Stopping the run

Click Stop in the line of the runs, or press Cmd+F2 / Ctrl+F2. The run turns `stopped`, the Model pane keeps the last
model, and the Send button is greyed out. The container keeps running, so the next run starts fast. Stop runtime in the
first line stops and removes the container.

## 1.10 Changing the model

Give the lamp a way back from `broken`.

1. Add an event at the end of `lamp-events.kmeta`:

   ```kmeta
   type "repair" "Repairs a broken lamp" {
   }
   ```

2. In `lamp.kstates`, add a transition at the end of the `TRANSITIONS` block. Ctrl+M inside the quotes of a state
   name proposes the states.

   ```kstates
   TRANSITION "broken" -> "off" {
       CONDITION { EVENT("service", "repair") }
   }
   ```

3. Click Run again in the line of the runs, and Run in the dialog Run. A run saves every open file first.
4. Send `smash`, then `repair`, both in the domain `service`. The lamp goes from `broken` back to `off`. The spare bulb
   stays gone, because nothing creates it again.

If a file has an error, the run does not start, and a dialog lists the errors. [4.4 Validating](4.4-validating.md)
describes what blocks a run.

## 1.11 Trying a bridge

1. Click Run again, tick `http.kbridge.json` in the dialog Run and click Run. The Log pane lists the bridge below the
   deployment.
2. In a terminal, press the wall switch and make the lamp dimmer:

   ```bash
   curl -X POST http://127.0.0.1:8090/press
   curl -X POST http://127.0.0.1:8090/dim -d '{"bright": false}'
   ```

   The lamp goes on, then down to `watts = 30`, as if the events had been sent in the Events pane.

The other two bridge files need a partner. Without it the bridge shows as failed, and the run goes on.

| File | Needs | Does |
|---|---|---|
| `mqtt.kbridge.json` | An MQTT broker on `localhost:1883`. | Takes presses on `home/lamp/press` and dim steps on `home/lamp/dim`. Publishes the power on `home/lamp/watts` and the active states on `home/lamp/state`. |
| `ros.kbridge.json` | A rosbridge server on `localhost:9090`, from the ROS package `rosbridge_suite`. | Takes presses on `/lamp/press` and dim steps on `/lamp/dim`. Publishes the power on `/lamp/watts` and the active states on `/lamp/state`. |

[4.7 Bridges](4.7-bridges.md) describes the bridge files and their form.

## 1.12 Closing and cleaning up

Quit with Cmd+Q, or close the window. The IDE asks about unsaved changes, stops every run and removes the runtime
container. To undo your changes to the example, run `git restore examples/lamp` and delete the `.vis` files and the
file `.karpfenproject` that the IDE wrote into the folder.

The container is also removed when the IDE is stopped from outside, for example with Ctrl+C on the Gradle command.
Only a process that is killed outright leaves it behind.

The image stays, so the next run starts in seconds. To free the disk space, remove it. The first command removes
containers left behind by a killed IDE, and prints an error when there are none.

```bash
docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)
docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)
```
