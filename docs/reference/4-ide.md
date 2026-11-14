# 4 The IDE

karpfen IDE is a desktop program for the files of a karpfen system. It opens a folder as a project, edits the KMeta,
KModel and KStates files and the environment descriptors, and checks them while you type. It draws each of the three
kinds of model file as a diagram that can also be edited. It validates the whole project, runs an environment
descriptor in a karpfen runtime inside a Docker container of its own, shows the model and the active states while the
model runs, sends events into the run, halts the run at breakpoints and connects it to HTTP, MQTT and ROS through
bridges. The same program validates and runs a project from a terminal, without a window.

The window has a menu bar, an editor area in the middle, tool windows around it and a status bar at the bottom. Each
tool window can be shown and hidden in the View menu, and its border to the editor area can be dragged. The Runtime
tool window can also be moved into a window of its own.

| Part | Place | What it shows |
|---|---|---|
| Project | Left | The files of the folder in three sections: the project file and the descriptors, the folder, the bridge files. |
| Editor area | Middle | One tab per open file. Below the editor a bar holds Text, Split and Diagram for files with a diagram, the Validate button and the font size of the editor. |
| Outline | Right | What the active file is made of: the types of a metamodel, the objects of a model, the states and transitions of a state machine. |
| Problems | Bottom | The problems of the project in the groups Local and Global. It is hidden while there is no problem. |
| Language Tools Output | Bottom | The log of the language tools that read and check the files. |
| Runtime | Bottom | The runtime container, the runs, the debugger and three panes for the model, the events and the log of the selected run. |
| Status bar | Bottom edge | On the left the problems, `Analyzing…` while the files are checked, the state of the runtime and unseen messages of the language tools. On the right the line and column, the language, the line separator, the encoding, the counts of errors and warnings, and the zoom of the whole window. |

The window has two zoom controls. `Zoom 100%` at the right end of the status bar, between a minus and a plus, scales
everything in the window. `Font size 13` in the bar below the editor scales the text of the editors only. A click on
either size sets it back to normal.

The sections of this chapter follow the work with a project.

| Section | Subject |
|---|---|
| [4.1 Projects](4.1-projects.md) | The folder as a project, the kinds of files, the environment descriptor, the project file and the language features. |
| [4.2 Text editor](4.2-text-editor.md) | Colours, completion, hover, navigation, the Outline, the Problems tool window and the keys of the editor. |
| [4.3 Diagrams](4.3-diagrams.md) | The statechart, the class diagram and the object diagram: how to read them, edit them and arrange them by hand. |
| [4.4 Validating](4.4-validating.md) | What is checked while you type, what a validation of the whole project adds, and what blocks a run. |
| [4.5 Running](4.5-running.md) | The Docker container, the run dialog, the Runtime tool window, sending events, observation and captured logs. |
| [4.6 Debugging](4.6-debugging.md) | Breakpoints and the debugger bar with Pause, Resume and Step. |
| [4.7 Bridges](4.7-bridges.md) | Bridge files for HTTP, MQTT and ROS, their form and their routes. |
| [4.8 Command line](4.8-command-line.md) | `karpfen-ide validate` and `karpfen-ide run` without a window. |

The languages themselves are the subject of
[2 Modeling languages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2-modeling-languages.md),
and what a running model does is the subject of
[3 Execution](https://github.com/karpfenproject/karpfen-runtime/blob/main/docs/reference/3-execution.md).
