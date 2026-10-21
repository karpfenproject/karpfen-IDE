# P1 — Manual smoke test (about 10 minutes)

Status: v1.0, 2026-10-21 (INTEGRATION). Spec §11 item 7. The automated tests cover the editor, the workbench and the
CLI headless (`./gradlew build`); this checklist covers what only a person at a real Mac can check: the native menu bar,
typing on a German keyboard layout (Option combinations, dead keys, the press-and-hold accent menu), real focus and
mouse handling, the file watcher and quitting. Each step names the expected result; note the step number of anything
that differs.

## 0. Setup (1 minute)

Select the **German** input source (menu bar ▸ input menu ▸ "Deutsch") and run, from the repository root:

```bash
rm -rf /tmp/karpfen-smoke /tmp/karpfen-smoke-home
cp -R core/core-document/src/testFixtures/resources/examples /tmp/karpfen-smoke
KARPFEN_IDE_HOME=/tmp/karpfen-smoke-home ./gradlew :app:ide:run --args=/tmp/karpfen-smoke
```

`KARPFEN_IDE_HOME` keeps your own settings and log untouched; settings and log go to `/tmp/karpfen-smoke-home`. The
examples are a copy, so edit freely. Below, `robot/` means `karpfen-runtime/single_robot_example/`.

Keys on the German layout used below: `{` Option+8, `}` Option+9, `[` Option+5, `]` Option+6, `|` Option+7,
`\` Option+Shift+7, `@` Option+L, `~` Option+N (dead key), `/` Shift+7, `"` Shift+2, `$` Shift+4, `(` Shift+8,
`>` Shift+`<`, `#` its own key next to Ä.

## 1. Start (30 seconds)

1. The window title is `karpfen-smoke — karpfen IDE`; the macOS menu bar shows **karpfen IDE, File, Edit, View,
   Navigate**.
2. The Project tree shows `karpfen-dsl-tools` and `karpfen-runtime`; the Problems view says `Errors (0)`; the status
   bar shows 0 errors and 0 warnings (the examples are clean).
3. Double-click `robot/cleaning_robot.kstates` in the tree: it opens in a tab; the Outline shows the state machine with
   its states, transitions and macros; the status bar shows `Ln 1, Col 1`, `KStates`, `LF`, `UTF-8`.

## 2. Typing on the German layout (2 minutes)

In `cleaning_robot.kstates`, put the caret at the end of the line `INITIAL STATE "ready" { }` and press Enter.

1. Type `STATE "test" ` then `{` (Option+8): the editor inserts `{}` with the caret between them. Press Enter: the `}`
   moves to its own line and the caret is indented by 4 more spaces. The `"` was auto-closed too, and typing the
   closing `"` stepped over it instead of adding a second one.
2. Type `DO ` + `{` + Enter, then `SET("log", EVAL { return [1, 2] })`, using Option+5/6 for the brackets: `(` and
   `{` auto-close and typing their closers steps over them, so each character appears exactly once; Option+5/6/8 run
   no command.
3. Press Enter and type a comment line: `// | \ @ ~` (Option+7, Option+Shift+7, Option+L, Option+N then Space): exactly
   these characters appear. Option+L must not open Go to Line (that is Cmd+L).
4. Dead keys and input methods, on the same comment line: `^` then Space (`^`), `´` then `e` (`é`), Shift+`´` then `a`
   (`à`), Option+N then `n` (`ñ`), the keys `ä ö ü ß`, and press and hold `e` and pick a variant from the accent menu
   with its number key: each produces exactly that one character at the caret; while a dead key waits, its mark is
   drawn at the caret.
5. Type `(` (Shift+8): `()` appears with the caret between; Backspace removes both in one step.

## 3. Toggle comment (30 seconds)

1. Caret on the line `APPEND("log", "Reacting to obstacle")`: **Cmd+Shift+7** (= Cmd+/ on the German layout) puts
   `// ` before the text; again removes it.
2. Caret inside an EVAL body (e.g. the line `distance = @point_to_point_distance(…)` in `MACRO
   "circular_bounding_box_distance"`): Cmd+Shift+7 inserts `# ` (Python comment, because `//` is a karpfen comment
   there); again removes it.
3. Each toggle is undone by exactly one Cmd+Z. Edit ▸ Toggle Comment in the menu bar does the same; note how the menu
   shows its shortcut (⌘/ or ⌘⇧7).

## 4. Completion (1.5 minutes)

1. In `TRANSITIONS { … }` start a new line and type `TRANSITION "`: the completion list opens with the state names
   (at most 12 rows, kind badge, label, detail). Type `ob`: only `observe` remains; Enter inserts it. Arrow keys move
   the selection; Escape closes the list.
2. Continue with ` -> "` (`>` is Shift+`<`): the state names open again; pick one with the arrow keys and Enter.
3. In any EVAL body type `$(`: the list shows the properties and relations of `Robot` (e.g. `boundingBox`,
   `d_closest_wall`); accept `boundingBox`, type `->`: the members of `TwoDObject` follow.
4. In an EVAL body type `@po`: the macros `point_to_point_distance` and `point_to_line_distance` are offered.
5. Ctrl+Space opens completion at the caret. **Note:** if macOS uses Ctrl+Space to switch input sources (System
   Settings ▸ Keyboard ▸ Keyboard Shortcuts ▸ Input Sources), the IDE never receives it; record whether it worked.
6. Clicking elsewhere in the text or moving the caret out of the completed word closes the list.

Undo your edits of steps 2–4 with Cmd+Z until the tab's dirty dot disappears (or keep them for step 7).

## 5. Navigation (1.5 minutes)

1. Cmd+click on `observe` in `TRANSITION "ready" -> "observe"`: the caret jumps to `STATE "observe"`. With the caret on
   a state reference, Cmd+B (Navigate ▸ Go to Definition) does the same.
2. Cmd+click on `Robot` in `STATEMACHINE ATTACHED TO "Robot"`: `robot/cleaning_robot.kmeta` opens in a new tab with the
   caret on `type "Robot"`.
3. Rest the mouse 0.6 s on `d_closest_wall` inside `$(d_closest_wall)`: a tooltip shows its type (`number`); on a state
   name it shows the state's hierarchy path.
4. Outline: click `drive slow`: the editor scrolls there and puts the caret on the name. Move the caret into another
   state: the Outline highlights the node containing the caret.
5. Cmd+L, type `120:5`, Enter: the caret is at line 120, column 5 (status bar).
6. Cmd+F, type `obstacle`: the first match is selected and the counter shows `1/n`; Enter and Shift+Enter move through
   the matches (off-screen matches scroll into view); Escape closes the bar and typing goes to the editor again;
   Cmd+G / Shift+Cmd+G find the next / previous match.

## 6. Problems (1.5 minutes)

1. In `cleaning_robot.kstates` delete the `{` after `STATE "observe"`: within a second a red wavy underline appears,
   the Problems view lists the error under `karpfen-runtime/single_robot_example/cleaning_robot.kstates` with its
   `line:col`, and the status bar's error count goes up. Rest the mouse on the underline: the tooltip shows the
   parser's message. Cmd+Z: all three disappear.
2. Open `robot/cleaning_robot.kmodel` and change the first `prop("diameter")` to `prop("radius")`: the status bar shows
   `Analyzing…` briefly, then a dsl-tools error appears in Problems and in the editor.
3. Double-click the error in Problems: the editor shows the file with the caret at the error. The `Errors` toggle in
   the Problems header hides and shows it.
4. Cmd+Z in the model: the error disappears from the editor, Problems and the status bar.

## 7. Undo and redo (1 minute)

1. In any open file type `abc`, wait two seconds, type `def`. One Cmd+Z removes `def` only (typing within one second
   is one step, a pause starts a new one); a second Cmd+Z removes `abc`; Cmd+Shift+Z brings `abc` back, with the caret
   where it was.
2. Edit ▸ Undo clicked in the macOS menu bar undoes exactly one step as well, and so does one Cmd+Z with the focus in
   the editor (the editor handles it; the menu item must not run a second undo).
3. Undo back to the saved text: the tab's dirty dot disappears.

## 8. Save and close (1 minute)

1. Make an edit: the tab shows the dirty dot. Cmd+S: the dot disappears; in a terminal, `tail -3` of the file shows
   the edit and the file still has LF line endings (`file <path>` does not mention CRLF).
2. Edit two files; Cmd+Option+S (File ▸ Save All) saves both.
3. Edit again and press Cmd+W: the "Unsaved Changes" dialog offers Save, Don't Save and Cancel. Cancel keeps the tab;
   Cmd+W again and Don't Save closes it without writing.

## 9. External changes on disk (1.5 minutes)

The JDK's file watcher on macOS polls, so a change on disk shows up after **up to about 10 seconds**.

1. With `cleaning_robot.kstates` open and saved, run
   `printf '// external\n' >> /tmp/karpfen-smoke/karpfen-runtime/single_robot_example/cleaning_robot.kstates`: the
   editor shows the new last line; the tab stays clean; one Cmd+Z takes the reload back (one undoable step).
2. Make an unsaved edit in that file, then run the `printf` again: a banner "cleaning_robot.kstates changed on disk
   while it has unsaved changes." offers **Reload** and **Keep mine**. Reload shows the disk content.
3. `touch /tmp/karpfen-smoke/karpfen-runtime/new_machine.kstates`: the file appears in the Project tree.
4. With `new_machine.kstates` open, delete it in the terminal: the banner "… was deleted on disk." offers **Close** and
   **Keep mine**. Keep mine keeps the tab and marks it dirty; Cmd+S recreates the file.

## 10. Quit and restart (30 seconds)

1. Make an unsaved edit and press **Cmd+Q**: the "Unsaved Changes" dialog appears (Cmd+Q, the application menu's Quit
   and logout run the same close request as the window's close button). Cancel keeps the IDE running.
2. Cmd+Q again, Don't Save: the IDE exits.
3. Start the IDE again, move and resize the window and quit. Then run only the `./gradlew` line of step 0, without
   `--args`: the window comes back at the same position and size, and File ▸ Open Recent lists `/tmp/karpfen-smoke`.

## Reporting

For every deviation note the step number, what you did and what happened. Attach
`/tmp/karpfen-smoke-home/logs/ide.log`; after a run without failures it contains no `ERROR` line
(`grep ERROR /tmp/karpfen-smoke-home/logs/ide.log` prints nothing). dsl-tools prints its models to the terminal that
runs Gradle while it analyzes; that output is expected and not an error.
