# karpfen IDE

karpfen IDE is a desktop IDE for the karpfen modeling languages: metamodels (`.kmeta`), models (`.kmodel`), state
machines (`.kstates`) and the environment descriptors (`.kenv.json`) that tie them together. It checks the files while
you type, runs them in a karpfen runtime, and shows the model change while you send events. It is written in Kotlin
with Compose Multiplatform for Desktop and uses karpfen-dsl-tools as its language core.

karpfen IDE is a research prototype and changes often. Editing and running models work today, and a state machine,
a metamodel and a model each show as a diagram. The diagrams cannot edit the text yet, and the bridges to MQTT and ROS
are not built yet.

## Quick start

You need a JDK 21, git and a running local Docker engine, such as Docker Desktop.

```bash
git clone --recurse-submodules https://github.com/karpfenproject/karpfen-IDE.git
cd karpfen-IDE
./gradlew build
./gradlew :app:ide:run --args="$PWD/examples/lamp"
```

`./gradlew build` compiles every module and runs the tests. The last line starts the IDE on the lamp example. The
folder must be an absolute path. In the IDE, press Shift+F10 to run the lamp. The first run builds the runtime image in
Docker, which takes a few minutes once.

## Documentation

- [Getting started](docs/getting-started.md): the idea behind karpfen, the modes of the IDE and a walkthrough with the
  lamp example.
- [Architecture](docs/archdoc.md): the modules and how they depend on each other.
- [Developer notes](docs/devdoc.md): the key design decisions, specific behaviour, open points and details that must
  not be lost.

## License

Apache License 2.0, see [LICENSE](LICENSE).
