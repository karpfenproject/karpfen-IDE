# IDE-authored fixtures

Unlike `../examples/` (unmodified copies from the karpfen repositories, see `../examples/NOTICE.md`), the files below
this directory were written for karpfen IDE's own tests (Apache License 2.0, like the rest of this repository).

`descriptors/` holds environment descriptors (`*.kenv.json`) for the files of
`examples/karpfen-runtime/single_robot_example`. `DescriptorExamples` in the test fixtures of core-document reads them;
its constants say what each descriptor holds and which findings the workspace reports for it.
