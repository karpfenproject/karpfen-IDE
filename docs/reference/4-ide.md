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
| Editor area | Middle | One tab per open file. Below the editor a bar holds Text, Split and Diagram for files with a diagram, the Validate button and the font size of the editor. Above the diagram of a fragment a bar names the files that use it. |
| Outline | Right | What the active file is made of: the types of a metamodel, the objects of a model, the states and transitions of a state machine, and the header of a fragment. |
| Problems | Bottom | The problems of the project in the groups Local and Global. It is hidden while there is no problem. |
| Language Tools Output | Bottom | The log of the language tools that read and check the files. |
| Runtime | Bottom | The runtime container, the runs, the debugger and three panes for the model, the events and the log of the selected run. |
| Status bar | Bottom edge | On the left the problems, `Analyzing…` while the files are checked, the state of the runtime and unseen messages of the language tools. On the right the line and column, the language, the line separator, the encoding, the counts of errors and warnings, and the zoom of the whole window. |

The window has two zoom controls. `Zoom 100%` at the right end of the status bar, between a minus and a plus, scales
everything in the window. `Font size 13` in the bar below the editor scales the text of the editors only. A click on
either size sets it back to normal.

The menu bar has the menus File, Edit, View, Navigate and Run. The table lists every entry in the order of the menu
bar. Open Recent is a submenu with one entry per folder, and Run > Language Features is a submenu whose entries read
`Switch On` or `Switch Off` before the name of the feature, after what a click does.

| Menu | Entry | What it does | Described in |
|---|---|---|---|
| File | Open Folder… | Opens a folder as the project. | [4.1 Projects](4.1-projects.md) |
| File | Open Recent | Opens one of the folders opened before. | [4.1 Projects](4.1-projects.md) |
| File | Save | Saves the file of the active tab. | [4.2.8 Saving and changes outside the IDE](4.2-text-editor.md#428-saving-and-changes-outside-the-ide) |
| File | Save All | Saves every file with unsaved changes. | [4.2.8 Saving and changes outside the IDE](4.2-text-editor.md#428-saving-and-changes-outside-the-ide) |
| File | Close Tab | Closes the active tab. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Undo | Takes back the last edit, also one made in a diagram. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Redo | Makes an edit that was taken back again. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Cut | Moves the selection to the clipboard. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Copy | Copies the selection to the clipboard. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Paste | Inserts the clipboard at the caret. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Select All | Selects the whole text of the editor. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Find… | Opens the search bar above the text. | [4.2.7 Find and replace](4.2-text-editor.md#427-find-and-replace) |
| Edit | Replace… | Opens the search bar with a field for the replacement. | [4.2.7 Find and replace](4.2-text-editor.md#427-find-and-replace) |
| Edit | Find Next | Selects the next match. | [4.2.7 Find and replace](4.2-text-editor.md#427-find-and-replace) |
| Edit | Find Previous | Selects the match before. | [4.2.7 Find and replace](4.2-text-editor.md#427-find-and-replace) |
| Edit | Toggle Comment | Comments the selected lines out or in. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Duplicate Line | Copies the line of the caret below itself. | [4.2.10 Keys](4.2-text-editor.md#4210-keys) |
| Edit | Complete Code | Shows the completion at the caret. | [4.2.2 Completion](4.2-text-editor.md#422-completion) |
| View | Project | Shows or hides the Project tool window. | [4.1.5 The project tree](4.1-projects.md#415-the-project-tree) |
| View | Outline | Shows or hides the Outline tool window. | [4.2.5 Outline](4.2-text-editor.md#425-outline) |
| View | Problems | Shows or hides the Problems tool window. | [4.2.6 Problems](4.2-text-editor.md#426-problems) |
| View | Language Tools Output | Shows or hides the log of the language tools. | The table of the window above |
| View | Runtime | Shows or hides the Runtime tool window. | [4.5.4 The Runtime tool window](4.5-running.md#454-the-runtime-tool-window) |
| View | Light Theme | Draws the window in light colours. | [4.1.6 The project file](4.1-projects.md#416-the-project-file) |
| View | Dark Theme | Draws the window in dark colours. | [4.1.6 The project file](4.1-projects.md#416-the-project-file) |
| Navigate | Go to Line… | Moves the caret to a line by its number. | [4.2.7 Find and replace](4.2-text-editor.md#427-find-and-replace) |
| Navigate | Go to Definition | Leads from a name to its declaration. | [4.2.4 Go to definition](4.2-text-editor.md#424-go-to-definition) |
| Run | Run | Validates, deploys and starts an environment descriptor. | [4.5.2 Starting a run](4.5-running.md#452-starting-a-run) |
| Run | Stop | Stops the selected run. | [4.5.5 Stopping a run](4.5-running.md#455-stopping-a-run) |
| Run | Validate Project | Checks the whole project and shows the report. | [4.4.3 Starting a validation](4.4-validating.md#443-starting-a-validation) |
| Run | Pause | Halts the selected run at the end of its tick. | [4.6.3 The debugger line](4.6-debugging.md#463-the-debugger-line) |
| Run | Resume | Lets a halted run go on up to the next breakpoint. | [4.6.3 The debugger line](4.6-debugging.md#463-the-debugger-line) |
| Run | Step | Lets a halted run go on to the next breakpoint or the end of the tick. | [4.6.3 The debugger line](4.6-debugging.md#463-the-debugger-line) |
| Run | Start Runtime | Starts the runtime container without a run. | [4.5.1 The runtime container](4.5-running.md#451-the-runtime-container) |
| Run | Stop Runtime | Stops and removes the runtime container. | [4.5.1 The runtime container](4.5-running.md#451-the-runtime-container) |
| Run | Configure Bridge | Opens the form of a bridge, or of a new one. | [4.7.4 The bridge form](4.7-bridges.md#474-the-bridge-form) |
| Run | Language Features > Histories | Switches histories on or off. | [4.1.7 Language features](4.1-projects.md#417-language-features) |
| Run | Language Features > Tick limits | Switches tick limits on or off. | [4.1.7 Language features](4.1-projects.md#417-language-features) |
| Run | Language Features > Breakpoints | Switches breakpoints and the debugger on or off. | [4.1.7 Language features](4.1-projects.md#417-language-features) |
| Run | Language Features > Macros | Switches macros on or off. | [4.1.7 Language features](4.1-projects.md#417-language-features) |
| Run | Language Features > Splits and joins | Switches splits and joins on or off. | [4.1.7 Language features](4.1-projects.md#417-language-features) |
| Run | Language Features > Parallel branches on threads | Switches the runtime feature TrueParallel on or off for every run. | [4.1.7 Language features](4.1-projects.md#417-language-features) |

Light Theme and Dark Theme switch the colours of the whole window at once. The choice is stored in the project file as
the key `theme` with the value `light` or `dark`. Without that line, or with `theme = system`, the window follows the
colours of the operating system, and only an edit of the line brings that back.

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
