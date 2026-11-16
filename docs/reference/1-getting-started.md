# 1 Getting started

karpfen describes a system in a few text files and runs it. This chapter uses the lamp example project, the model of a
lamp with a wall switch. You open the project in the IDE, run it, send events to it and connect it to HTTP.

Where a key differs between the systems, the key for macOS is written first and the key for Windows and Linux second,
for example Cmd+S / Ctrl+S.

## 1.1 What karpfen is

karpfen has three parts.

| Part | What it is | Reference |
|---|---|---|
| The modeling languages | KMeta for the types of a domain, KModel for the objects, KStates for the state machines that change the objects. | [2 Modeling languages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2-modeling-languages.md) |
| The runtime | A server that runs a model with its state machines in ticks. Events come in from outside, and clients watch the model change. | [3 Execution](https://github.com/karpfenproject/karpfen-runtime/blob/main/docs/reference/3-execution.md) |
| The IDE | A desktop program that edits the files, checks them while you type and draws them as diagrams. It runs them in its own runtime and shows the run. | [4 The IDE](4-ide.md) |

A system in karpfen is a folder of these files.

| File | What it holds |
|---|---|
| Metamodel, `.kmeta` | The types of the domain with their properties and relations. |
| Event metamodel, a second `.kmeta` | One type per event. The properties of a type are the payload of its event. |
| Model, `.kmodel` | The objects of the domain, each with a key such as `lamp`. |
| State machine, `.kstates` | The behaviour of one object. Entering a state runs actions that change the model. Transitions wait for events and conditions. |
| Fragment, `.frag.kstates` | A page of a state machine as defined in [2.3.12 Pages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.3-kstates.md#2312-pages). It holds a piece of behaviour that a container state of another state machine runs as its inner states. |
| Environment descriptor, `.kenv.json` | Which metamodel, events, model and state machines run together, and which object each state machine is attached to. |
| Bridge, `.kbridge.json` | A connection of the running model to HTTP, MQTT or ROS. |

The descriptor and the bridge files belong to the IDE, and the keys of the descriptor are listed in
[4.1 Projects](4.1-projects.md) together with everything else that makes a folder a project.

## 1.2 What you need

- Docker Desktop or another local Docker engine. It must be running, because the IDE runs every model in a Docker
  container of its own.
- The repository karpfen-IDE. The lamp example project of this chapter is its folder `examples/lamp`. The pages
  example project in `examples/pages` models a lamp whose state machine is composed of fragments, listed in its
  descriptor in the way that [4.1.2.5 Fragments](4.1-projects.md#4125-fragments) shows.
- A JDK 21 and the submodules of the repository if you start the IDE from the sources. Gradle itself runs on JDK 17 or
  newer and downloads a JDK 21 if none is installed. An installed IDE brings its own Java and needs neither the JDK nor
  the submodules.

```bash
git clone --recurse-submodules https://github.com/karpfenproject/karpfen-IDE.git
cd karpfen-IDE
```

If the repository was cloned without its submodules, `git submodule update --init --recursive` fetches them.

## 1.3 Starting the IDE

The IDE is either installed with an installer or started from the sources.

### 1.3.1 From an installer

The [releases of karpfen-IDE](https://github.com/karpfenproject/karpfen-IDE/releases) hold one installer per system.
GitHub builds them from a release tag.

| System | File | Installing | Program |
|---|---|---|---|
| macOS, Apple silicon | `karpfen-ide-<version>-macos-arm64.dmg` | Open the file and drag `karpfen-ide` to Applications. | `/Applications/karpfen-ide.app` |
| macOS, Intel | `karpfen-ide-<version>-macos-x64.dmg` | Open the file and drag `karpfen-ide` to Applications. | `/Applications/karpfen-ide.app` |
| Windows | `karpfen-ide-<version>-windows-x64.msi` | Open the file. | `karpfen-ide` in the Start menu, or `C:\Program Files\karpfen-ide\karpfen-ide.exe` |
| Debian and Ubuntu | `karpfen-ide-<version>-linux-x64.deb` | `sudo apt install ./karpfen-ide-<version>-linux-x64.deb` | `/opt/karpfen-ide/bin/karpfen-ide` |

The installers are not signed. Since macOS and Windows stop an unsigned program, you have to allow the IDE by hand
once.

On macOS the first start fails with a message that macOS could not verify the app. Open *System Settings*, go to
*Privacy & Security* and click the button *Open Anyway* next to the entry for `karpfen-ide`, then start the app again.

If macOS says instead that the app is damaged, *Privacy & Security* shows no such entry. In that case remove the mark
that the download left on the app with this command in a terminal, and start the app again.

```bash
xattr -dr com.apple.quarantine /Applications/karpfen-ide.app
```

On Windows the installer stops at the message "Windows protected your PC". Click *More info* and then the button
*Run anyway*.

On Debian and Ubuntu nothing has to be allowed.

Once the IDE is installed, start it and open the folder `examples/lamp` of the repository with the button
*Open Folder…*.

### 1.3.2 From the sources

On macOS and Linux this command, run from the root of the repository, builds the IDE and starts it on the lamp
example project.

```bash
./gradlew :app:ide:run --args="$PWD/examples/lamp"
```

On Windows the folder is written with `%CD%` and backslashes.

```text
gradlew.bat :app:ide:run --args="%CD%\examples\lamp"
```

The path of the folder must be absolute, and both commands make it absolute with the current directory in front. If
the folder is left out, the IDE opens on its welcome view with the button *Open Folder…* and the list of recent folders.

The first build takes a few minutes. The command keeps running for as long as the IDE is open.

`./gradlew :app:ide:createDistributable` builds the IDE as a program of its own into
`app/ide/build/compose/binaries/main/app/`. The name of the program file on each system is given in
[4.8 Command line](4.8-command-line.md).

## 1.4 The lamp example project

The lamp example project in `examples/lamp` describes a small system, a living room with a lamp and a wall switch.

- A press of the switch turns the lamp on and off. The lamp always comes on at the full power of its bulb, 40 W.
- While the lamp is on, a dimmer turns it up and down in steps of 10 W, between 0 W and the full power.
- When the lamp is switched off, it forgets the dimming.
- A smash breaks the lamp at any time and throws away its spare bulb.
- The switch has its own state machine that moves up and down with each press.

The descriptor sets a tick delay of 500 ms so that each change can be watched as it happens. It also lets an event wait
up to 5 seconds for a state machine that takes it.

| File | Content |
|---|---|
| `lamp.kmeta` | The types `Bulb`, `Switch`, `Lamp` and `Room`. |
| `lamp-events.kmeta` | The events `press`, `dim` with the payload `bright`, and `smash`. |
| `lamp.kmodel` | The room `living room` with the lamp `lamp`, its bulbs `bulb` and `spare`, and the switch `wallSwitch`. |
| `lamp.kstates` | The state machine of the lamp. Its states are `off`, `on` with the inner states `full`, `brighter` and `dimmer`, and `broken`. |
| `switch.kstates` | The state machine of the switch, with the states `down`, `up` and `back down`. |
| `lamp.kenv.json` | The descriptor. It attaches `lamp.kstates` to `lamp` and `switch.kstates` to `wallSwitch`. |
| `http.kbridge.json`, `mqtt.kbridge.json`, `ros.kbridge.json` | One bridge of each kind. |

## 1.5 Looking around

Once the IDE is started as in [1.3 Starting the IDE](#13-starting-the-ide), its window shows the folder of the lamp
example project. The *Project* tool window lists the descriptor `lamp.kenv.json` at the top, the five model files in
the middle and the three bridge files at the bottom. The status bar shows `Problems` without an icon and
`Runtime: stopped`.

1. Double-click `lamp.kstates`. The file opens with coloured keywords, and the tool window *Outline* lists its states
   and transitions.
2. Click the button *Split* in the bar below the editor. The statechart appears next to the text. Its initial state is
   `off`, and the state `on` holds three inner states. The caption of a transition reads like `1: switch/press`. It
   gives the priority of the transition and the event the transition waits for.
3. Click a state in the diagram. The editor selects its name in the text.
4. Drag the state `broken`. A grid of dots appears, and the state moves on it. The IDE keeps such a hand arrangement in
   a file next to the model file. In the *Project* tool window `lamp.kstates` can now be expanded, and it shows
   `lamp.kstates.vis` below it.
5. Open `lamp.kmeta` and click *Split*. A class diagram appears. A line with a filled diamond at `Lamp` leads to `Bulb`,
   and a line with an open arrowhead leads from `Lamp` to `Switch`.
6. Open `lamp.kmodel` and click *Split*. An object diagram appears. The box `bulb : Bulb` shows `maxWatts = 40`.
7. In `lamp.kstates`, Cmd+click / Ctrl+click on `Lamp` in `STATEMACHINE ATTACHED TO "Lamp"`. `lamp.kmeta` opens at
   the type.
8. Open `lamp.kenv.json` and rest the mouse on the key `tickDelayMs`. A tooltip explains the key.
9. In `lamp.kstates`, delete the `{` after `STATE "broken"`. A red wavy underline appears, and the status bar shows
   `Problems:` with a number and an error icon. After an undo with Cmd+Z / Ctrl+Z the file is clean again.

## 1.6 Running the lamp example project

1. Choose *Run > Run*, or press Shift+F10. On a Mac keyboard you may have to hold Fn to get F10.
2. The dialog *Run* lists the three bridge files and the checkbox *Capture run to file*. Leave the boxes unticked and
   click the button *Run*.

The *Runtime* tool window opens at the bottom and takes three quarters of the window's height. Its second line shows a
run named `lamp`, and the label of the run changes from `preparing` to `running`.

On the very first run the first line of the tool window reads `Docker: building the runtime image` for a few minutes,
because the IDE has to build the image of the runtime before it can start a container from it. Once the container
runs, the line reads `Docker: running at 127.0.0.1:` followed by a port.

The *Log* pane starts with the active states `lamp: off` and `wallSwitch: down`. Below them the line `Deployment done`
gives the number of steps of the deployment, and a click on it shows the steps. The *Model* pane reads
`Observation on` and follows the model while it changes. After a click on its button *Expand all* the tree shows
`mode = "off"` for the lamp, `maxWatts = 40`, `watts = 0` and `lit = false` for the bulb, and `position = "down"` for
the switch.

If Docker is not running, the run turns `failed`, and the first line of the *Runtime* tool window names the problem in
red. In that case start Docker and click the button *Run again* in the line of the runs.

## 1.7 Sending events

Events are sent into the run from the *Events* pane. You choose a domain under *1. Domain* and an event under
*2. Event* and then click the button that reads *Send* followed by the name of the event. After each send the *Model*
pane and the *Log* pane show what the event changed.

| Step | Do this | What happens |
|---|---|---|
| 1 | Click the domain `switch`, the event `press`, and the button *Send press*. | The lamp goes on at full power: `lamp: on > full`, `mode = "on"`, `lit = true`, `watts = 40`. The switch is `up`. |
| 2 | Click the event `dim`. Replace `false` in the payload by `3`. | The form shows an error because `bright` is a boolean. The button *Send dim* is greyed out. |
| 3 | Write `false` again and click *Send dim*. | The log shows `lamp  on -> dimmer`, and the bulb has `watts = 30`. |
| 4 | Send it three more times, then once more. | The bulb goes down to `watts = 0`. The last send changes nothing, because the lamp cannot be dimmed below 0 W. |
| 5 | Change the payload to `"bright": true` and send it. | `lamp  on -> brighter`, `watts = 10`. Each further send adds 10 W, up to 40 W. |
| 6 | Dim a step, then send `press` twice. | The lamp goes off with `watts = 0`. It comes on at `watts = 40` again because switching it off forgot the dimming. |
| 7 | Click the domain `service` and send `smash`. | `lamp: broken`, `mode = "broken"`, `lit = false`, `watts = 0`. The spare bulb disappears from the tree. |
| 8 | Send `press` once more. | The switch moves, but the lamp stays broken. Its state machine has no transition out of `broken`. |

The descriptor lets an event wait 5 seconds for a state machine that takes it. A `dim` sent while the lamp is off takes
effect if a press turns the lamp on within these 5 seconds, and it expires otherwise.

## 1.8 Observing and pulling

1. In the *Model* pane, turn the switch *Observation* off. The status reads `Observation off`.
2. Send `press` twice. The tree and the active states stay as they are, since the runtime no longer reports the
   changes. The simplified log still shows the transitions.
3. Click the button *Pull*. The IDE reads the current model once, and the tree shows it with two more entries in
   `presses`.
4. Turn observation on again. The status reads `live at revision` with a number.

## 1.9 Stopping the run

Click the button *Stop* in the line of the runs, or press Cmd+F2 / Ctrl+F2. The run turns `stopped`. The *Model* pane
keeps the last model, and the button that sends events is greyed out.

The container of the runtime keeps running after the run has stopped. The next run starts fast because it does not
have to start a container. The button *Stop runtime* in the first line of the *Runtime* tool window stops the
container and removes it.

## 1.10 Changing the model

In the lamp example project a broken lamp stays broken, and these steps give it a way back to `off`.

1. Add an event at the end of `lamp-events.kmeta`.

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

3. Click the button *Run again* in the line of the runs, and then *Run* in the dialog *Run*. The files need not be
   saved first, because a run saves every open file before it starts.
4. Send `smash`, then `repair`, both in the domain `service`. The lamp goes from `broken` back to `off`. The spare bulb
   stays gone, because nothing creates it again.

If a file has an error, the run does not start, and a dialog lists the errors. Which findings block a run is described
in [4.4 Validating](4.4-validating.md).

## 1.11 Trying a bridge

1. Click *Run again*, tick `http.kbridge.json` in the dialog *Run* and click *Run*. The *Log* pane lists the bridge
   below the deployment.
2. In a terminal, press the wall switch and make the lamp dimmer with these two requests to the bridge.

   ```bash
   curl -X POST http://127.0.0.1:8090/press
   curl -X POST http://127.0.0.1:8090/dim -d '{"bright": false}'
   ```

   The lamp goes on and then down to `watts = 30`. The bridge turns each request into an event, and the lamp reacts
   exactly as it does to an event sent in the *Events* pane.

The other two bridge files need a partner to connect to. If the partner is missing, the bridge shows as failed, but
the run goes on without it.

| File | Needs | Does |
|---|---|---|
| `mqtt.kbridge.json` | An MQTT broker on `localhost:1883`. | Takes presses on `home/lamp/press` and dim steps on `home/lamp/dim`. Publishes the power on `home/lamp/watts` and the active states on `home/lamp/state`. |
| `ros.kbridge.json` | A rosbridge server on `localhost:9090`, from the ROS package `rosbridge_suite`. | Takes presses on `/lamp/press` and dim steps on `/lamp/dim`. Publishes the power on `/lamp/watts` and the active states on `/lamp/state`. |

[4.7 Bridges](4.7-bridges.md) describes the bridge files and their form.

## 1.12 Closing and cleaning up

Quit with Cmd+Q, or close the window. Before the IDE ends, it asks about unsaved changes, stops every run and removes
the runtime container.

The container is also removed when the IDE is stopped from outside, for example with Ctrl+C on the Gradle command.
Only a process that is killed outright leaves it behind.

To undo your changes to the lamp example project, run `git restore examples/lamp`. The `.vis` files and the file
`.karpfenproject` are new files that the IDE wrote into the folder, and `git restore` does not touch them. Delete them
by hand.

The runtime image stays, and with it the next run starts in seconds. The second of the two commands below removes the
image and frees its disk space. The first removes containers that a killed IDE left behind, and it prints an error if
there are none.

```bash
docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)
docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)
```
