# IDE-authored fixtures

Unlike `../examples/` (unmodified copies from the karpfen repositories, see `../examples/NOTICE.md`), the files below
this directory were written for karpfen IDE's own tests (Apache License 2.0, like the rest of this repository).

`descriptors/` holds environment descriptors (`*.kenv.json`, spec §4) for the files of
`examples/karpfen-runtime/single_robot_example`. They are read by
`io.karpfen.ide.core.document.fixtures.DescriptorExamples`:

| File | Content | Expected findings |
|---|---|---|
| `single_robot.kenv.json` | every key of the descriptor shape, naming the four example files | none |
| `unknown_key_and_missing_file.kenv.json` | unknown key `tickDelay`, `model` names a file that does not exist | WARNING `descriptor-unknown-key` at `tickDelay`, ERROR `descriptor-missing-file` at `missing.kmodel` |
| `invalid_json.kenv.json` | truncated JSON | ERROR `descriptor-invalid` |
