# Example files: origin and license

The `.kmeta`, `.kmodel` and `.kstates` files below this directory are unmodified copies of example files from two
karpfen repositories. They serve as test fixtures for karpfen IDE (spec `docs/p1-text-editing-spec.md` §10) and are
listed in `index.txt`.

| Folder | Origin repository | Commit | Copied paths |
|---|---|---|---|
| `karpfen-runtime/` | [karpfenproject/karpfen-runtime](https://github.com/karpfenproject/karpfen-runtime) | `d4c2059` (d4c20591689fb301f2af06f7f2965c86abf83e83) | `examples/single_robot_example`, `examples/delivery_drone_example`, `examples/autonomous_car_example`, `examples/drone_swarm_example` (only `*.kmeta`, `*.kmodel`, `*.kstates`) |
| `karpfen-dsl-tools/` | [karpfenproject/karpfen-dsl-tools](https://github.com/karpfenproject/karpfen-dsl-tools) | `859a95d` (859a95da0541e6c403994d22d38f8c8510720324) | `example/metamodel_dsl_example.kmeta`, `example/model_dsl_example.kmodel`, `example/statemachine_full_example/*` |

Copyright 2026 Karl Kegel. Both repositories, and therefore these files, are licensed under the Apache License,
Version 2.0 (the same license as karpfen IDE, see `LICENSE` in the repository root). You may obtain a copy of the
license at <http://www.apache.org/licenses/LICENSE-2.0>.

When the upstream examples change, copy them again, update the commits above and regenerate `index.txt`.
