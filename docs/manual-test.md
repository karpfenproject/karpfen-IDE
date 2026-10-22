# Manual smoke test

About 12 minutes at a Mac. The automated tests run the editor, the workbench and the command-line tool headless
(`./gradlew build`); this checklist covers what only a person at a real machine can check: the native menu bar, typing
on a German keyboard layout (Option combinations, dead keys, the press-and-hold accent menu), real focus and mouse
handling, the file watcher and quitting. Every step says what you should see. Note the section and the step of anything
that differs.

## Setup (1 minute)

Select the **German** input source (menu bar ▸ input menu ▸ "Deutsch") and run this from the repository root:

```bash
rm -rf /tmp/karpfen-smoke /tmp/karpfen-smoke-home
cp -R core/core-document/src/testFixtures/resources/examples /tmp/karpfen-smoke
KARPFEN_IDE_HOME=/tmp/karpfen-smoke-home ./gradlew :app:ide:run --args=/tmp/karpfen-smoke
```

`KARPFEN_IDE_HOME` keeps your own settings and log untouched: this run writes them to `/tmp/karpfen-smoke-home`. The
examples are a copy, so edit freely. Below, `robot/` stands for `karpfen-runtime/single_robot_example/`.

The German layout produces the characters used below with these keys: `{` Option+8, `}` Option+9, `[` Option+5,
`]` Option+6, `|` Option+7, `\` Option+Shift+7, `@` Option+L, `~` Option+N (a dead key), `/` Shift+7, `"` Shift+2,
`$` Shift+4, `(` Shift+8, `>` Shift+`<`, and `#` has its own key next to Ä.

## Start (30 seconds)

1. The window title is `karpfen-smoke — karpfen IDE`, and the macOS menu bar shows **karpfen IDE, File, Edit, View,
   Navigate**.
2. The Project tree shows `karpfen-dsl-tools` and `karpfen-runtime`, the Problems view says `Errors (0)`, and the
   status bar shows 0 errors and 0 warnings, because the examples are clean.
3. Double-click `robot/cleaning_robot.kstates` in the tree. It opens in a tab; the Outline shows the state machine with
   its states, transitions and macros; the status bar shows `Ln 1, Col 1`, `KStates`, `LF` and `UTF-8`.

## Typing on the German layout (2 minutes)

In `cleaning_robot.kstates`, put the caret at the end of the line `INITIAL STATE "ready" { }` and press Enter.

1. Type `STATE "test" ` and then `{` (Option+8). The editor inserts `{}` with the caret between the braces. Press
   Enter: the `}` moves to its own line and the caret is indented by 4 more spaces. The `"` was closed automatically
   too, and typing the closing `"` stepped over it instead of adding a second one.
2. Type `DO `, `{` and Enter, then `SET("log", EVAL { return [1, 2] })`, with Option+5 and Option+6 for the square
   brackets. `(` and `{` close automatically and typing their closers steps over them, so each character appears
   exactly once; Option+5, Option+6 and Option+8 run no command.
3. Press Enter and type a comment line `// | \ @ ~` (Option+7, Option+Shift+7, Option+L, then Option+N and Space).
   Exactly these characters appear. Option+L must not open Go to Line, which is Cmd+L.
4. On the same comment line, try dead keys and input methods: `^` then Space gives `^`, `´` then `e` gives `é`,
   Shift+`´` then `a` gives `à`, Option+N then `n` gives `ñ`; then type `ä ö ü ß`, and press and hold `e` and pick a
   variant from the accent menu with its number key. Each produces exactly that one character at the caret, and while
   a dead key waits, its mark is drawn at the caret.
5. Type `(` (Shift+8): `()` appears with the caret between the parentheses, and one Backspace removes both.

## Toggle comment (30 seconds)

1. With the caret on the line `APPEND("log", "Reacting to obstacle")`, press **Cmd+Shift+7** (Cmd+/ on the German
   layout): `// ` appears before the text. Pressing it again removes it.
2. With the caret inside an EVAL body, for example on the line `distance = @point_to_point_distance(…)` in
   `MACRO "circular_bounding_box_distance"`, Cmd+Shift+7 inserts `# `, the Python comment, because `//` is a karpfen
   comment there. Pressing it again removes it.
3. One Cmd+Z undoes each toggle. Edit ▸ Toggle Comment in the menu bar does the same; note how the menu shows its
   shortcut (⌘/ or ⌘⇧7).

## Completion (1.5 minutes)

1. Inside `TRANSITIONS { … }`, start a new line and type `TRANSITION "`. The completion list opens with the state names
   (at most 12 rows, each with a kind badge, label and detail). Type `ob`: only `observe` remains, and Enter inserts
   it. The arrow keys move the selection and Escape closes the list.
2. Continue with ` -> "` (`>` is Shift+`<`). The state names are offered again; pick one with the arrow keys and Enter.
3. In any EVAL body, type `$(`. The list shows the properties and relations of `Robot`, such as `boundingBox` and
   `d_closest_wall`. Accept `boundingBox` and type `->`: the members of `TwoDObject` follow.
4. In an EVAL body, type `@po`. The macros `point_to_point_distance` and `point_to_line_distance` are offered.
5. Press **Ctrl+M** (the Control key, not Cmd) with the caret anywhere in the text: completion opens at the caret and
   no line break or other character is typed. Ctrl+M opens completion on every platform; on Windows, Ctrl+Space does
   as well. On macOS, Ctrl+Space is left to the system, which uses it to switch input sources.
6. Clicking elsewhere in the text, or moving the caret out of the completed word, closes the list.

Undo the edits of this section with Cmd+Z until the tab's dirty dot disappears, or keep them for the undo section.

## Navigation (1.5 minutes)

1. Cmd+click `observe` in `TRANSITION "ready" -> "observe"`: the caret jumps to `STATE "observe"`. With the caret on a
   state reference, Cmd+B (Navigate ▸ Go to Definition) does the same.
2. Cmd+click `Robot` in `STATEMACHINE ATTACHED TO "Robot"`: `robot/cleaning_robot.kmeta` opens in a new tab with the
   caret on `type "Robot"`.
3. Rest the mouse for 0.6 seconds on `d_closest_wall` inside `$(d_closest_wall)`: a tooltip shows its type, `number`.
   On a state name it shows the state's hierarchy path.
4. In the Outline, click `drive slow`: the editor scrolls there and puts the caret on the name. Move the caret into
   another state: the Outline highlights the node that contains the caret.
5. Press Cmd+L, type `120:5` and Enter: the status bar shows line 120, column 5.
6. Press Cmd+F and type `obstacle`. The first match is selected and the counter shows `1/n`. Enter and Shift+Enter move
   through the matches and scroll off-screen matches into view. Escape closes the bar, and typing goes to the editor
   again. Cmd+G and Shift+Cmd+G find the next and the previous match.

## Problems (1.5 minutes)

1. In `cleaning_robot.kstates`, delete the `{` after `STATE "observe"`. Within a second a red wavy underline appears,
   the Problems view lists the error under `karpfen-runtime/single_robot_example/cleaning_robot.kstates` with its
   `line:col`, and the status bar's error count goes up. Rest the mouse on the underline: the tooltip shows the
   parser's message. Cmd+Z makes all three disappear.
2. Open `robot/cleaning_robot.kmodel` and change the first `prop("diameter")` to `prop("radius")`. The status bar shows
   `Analyzing…` briefly, then a dsl-tools error appears in Problems and in the editor.
3. Double-click the error in Problems: the editor shows the file with the caret at the error. The `Errors` toggle in
   the header of Problems hides and shows it.
4. Cmd+Z in the model: the error disappears from the editor, from Problems and from the status bar.

## Environment descriptors (2 minutes)

> **New in the current increment, being built in parallel with this document.** Environment descriptors become
> editable in the IDE. If the build you test still opens a descriptor in the system's default application, skip this
> section and note that. The steps describe the intended behaviour.

1. Right-click `robot/` in the Project tree and choose **New Environment File**, named `robot`. `robot.kenv.json`
   opens in an editor tab, not in another application. It names the four files of the folder, attaches the state
   machine to `turtle`, and is highlighted: keys, strings and numbers in their own colours.
2. Start a new line inside the outer object and press **Ctrl+M**: completion offers the descriptor's keys that are not
   there yet, for example `tickDelayMs` and `eventTtlMs`. Accept one and give it a value such as `50`.
3. Put the caret inside the quotes of the `"model"` value, delete the file name and press Ctrl+M: completion offers file
   paths relative to the descriptor, among them `cleaning_robot.kmodel`. Accept it.
4. Change the `"model"` value to `missing.kmodel`. An error underline appears at the file name, and Problems lists it
   under `robot.kenv.json`. Rename the key `tickDelayMs` to `tickDelay`: a warning for the unknown key appears at the
   key. Undo both changes with Cmd+Z until the underlines are gone.
5. Cmd+click `cleaning_robot.kstates` in the descriptor, or put the caret on it and press Cmd+B: the state machine opens
   in its tab.
6. Save the descriptor with Cmd+S. Problems shows no finding for it, and the status bar is back to 0 errors and
   0 warnings.

## Undo and redo (1 minute)

1. In any open file, type `abc`, wait two seconds and type `def`. One Cmd+Z removes only `def`, because typing within a
   second is one step and a pause starts a new one; a second Cmd+Z removes `abc`; Cmd+Shift+Z brings `abc` back, with
   the caret where it was.
2. Clicking Edit ▸ Undo in the macOS menu bar undoes exactly one step as well, and so does one Cmd+Z with the focus in
   the editor: the editor handles it, and the menu item must not run a second undo.
3. Undo back to the saved text: the tab's dirty dot disappears.

## Save and close (1 minute)

1. Make an edit: the tab shows the dirty dot. Press Cmd+S and the dot disappears. In a terminal, `tail -3` of the file
   shows the edit, and the file still has LF line endings (`file <path>` does not mention CRLF).
2. Edit two files and press Cmd+Option+S (File ▸ Save All): both are saved.
3. Edit again and press Cmd+W. The "Unsaved Changes" dialog offers Save, Don't Save and Cancel. Cancel keeps the tab;
   Cmd+W again and Don't Save closes it without writing.

## Changes on disk (1.5 minutes)

The JDK's file watcher on macOS polls, so a change on disk shows up after **up to about 10 seconds**.

1. With `cleaning_robot.kstates` open and saved, run
   `printf '// external\n' >> /tmp/karpfen-smoke/karpfen-runtime/single_robot_example/cleaning_robot.kstates`. The
   editor shows the new last line, the tab stays clean, and one Cmd+Z takes the reload back as one undoable step.
2. Make an unsaved edit in that file and run the `printf` again. A banner "cleaning_robot.kstates changed on disk while
   it has unsaved changes." offers **Reload** and **Keep mine**; Reload shows the content on disk.
3. Run `touch /tmp/karpfen-smoke/karpfen-runtime/new_machine.kstates`: the file appears in the Project tree.
4. With `new_machine.kstates` open, delete it in the terminal. The banner "… was deleted on disk." offers **Close** and
   **Keep mine**. Keep mine keeps the tab and marks it dirty, and Cmd+S creates the file again.

## Quit and restart (30 seconds)

1. Make an unsaved edit and press **Cmd+Q**. The "Unsaved Changes" dialog appears, because Cmd+Q, Quit in the
   application menu and logging out run the same close request as the window's close button. Cancel keeps the IDE
   running.
2. Press Cmd+Q again and choose Don't Save: the IDE exits.
3. Start the IDE again, move and resize the window, and quit. Then run only the `./gradlew` line of the setup, without
   `--args`: the window comes back at the same position and size, and File ▸ Open Recent lists `/tmp/karpfen-smoke`.

## Reporting

For every deviation, note the section and the step, what you did and what happened, and attach
`/tmp/karpfen-smoke-home/logs/ide.log`. After a run without failures the log contains no `ERROR` line
(`grep ERROR /tmp/karpfen-smoke-home/logs/ide.log` prints nothing). While it analyzes, dsl-tools still prints its
models to the terminal that runs Gradle; that output is expected for now and is not an error.
