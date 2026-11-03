# Synthesized models

Small karpfen models written for karpfen IDE's tests against the fake runtime and a real runtime (Apache License 2.0,
like the rest of this repository). `SynthesizedModels` in runtime-protocol's test fixtures reads them; `SynthesizedModel`
describes what each one exercises, and `SynthesizedModels.copyTo` writes one into a folder, optionally with a tick delay
and an event time to live in its descriptor. The files carry no comments, so the texts stay exactly what is uploaded.

Each folder holds a descriptor `<folder>.kenv.json` that names only the files and the attachments; the tick delay and
the time to live come from the deploy configuration. Every model has one root object, writes every scalar its machines
set, and uses no event domain named like an attached object key.

| Folder | Files | Machines | Event domains |
|---|---|---|---|
| `lamp/` | `lamp.kmeta`, `lamp-events.kmeta`, `lamp.kmodel`, `lamp.kstates`, `switch.kstates` | `lamp.kstates` on `lamp`, `switch.kstates` on `wallSwitch` | `switch` (`press`, `dim`), `service` (`smash`) |
| `metronome/` | `metronome.kmeta`, `metronome-events.kmeta`, `metronome.kmodel`, `metronome.kstates` | `metronome.kstates` on `metronome` | `beat` (`go`), `tick` (`burst done`, raised) |
| `depot/` | `depot.kmeta`, `depot-events.kmeta`, `depot.kmodel`, `depot.kstates` | `depot.kstates` on `depot` | `dock` (`receive`, `stack`, `label`, `scrap`) |
| `ring/` | `ring.kmeta`, `ring.kmodel`, `ring.kstates` | `ring.kstates` on `a` | none |

- **lamp**: deploy, lifecycle, injection with JSON and KModel payloads that decide a guard, forced states, the deletion
  of a model object, two machines reacting to one event, `knows` inlining, and text with spaces and an en dash.
- **metronome**: continuous change in bursts over several ticks, a parallel split and join, a list that only grows
  (which shows lost or misordered updates), and a raised event in a domain the events file does not declare.
- **depot**: the only model with Python (`EVAL` in its two creating actions), for objects created at run time and their
  own changes and deletion; its other two reactions need no Python.
- **ring**: two nodes that know each other, a reference loop the runtime cannot write as JSON and the IDE refuses to
  deploy; uploaded through raw requests only.

Failure inputs next to the models, never copied with a model: `lamp/lamp-unknown-type.kmodel` is the lamp's model with
the spare bulb typed `Bulbb`, which the runtime's parser refuses.

`SynthesizedModelsTest` checks that only the depot mentions `EVAL` or `MACRO`, only in its two creating actions, and
that every descriptor names files that exist.
