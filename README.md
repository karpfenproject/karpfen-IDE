# karpfen IDE

karpfen IDE is a desktop IDE for the karpfen modeling languages: metamodels (`.kmeta`), models (`.kmodel`), state
machines (`.kstates`) and the environment descriptors (`.kenv.json`) that tie them together. It checks the files while
you type, runs them in a karpfen runtime, and shows the model change while you send events. It is written in Kotlin
with Compose Multiplatform for Desktop and uses karpfen-dsl-tools as its language core.

karpfen IDE is a research prototype and changes often. Editing, validating, running and debugging models work
today, and bridges connect a running model to HTTP, MQTT and ROS. A state machine, a metamodel and a model each show
as a diagram that also edits the text. The IDE runs without a window as well, to validate a project and to run it
from a terminal.

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

Without a window:

```bash
./gradlew :app:ide:run --args="validate $PWD/examples/lamp"
./gradlew :app:ide:run --args="run $PWD/examples/lamp --bridge http.kbridge.json"
```

The first line validates the project and prints its findings. The second runs it with a bridge until Ctrl+C.

## Documentation

- [Getting started](docs/getting-started.md): the idea behind karpfen, the modes of the IDE, the use without a window
  and a walkthrough with the lamp example.
- [Architecture](docs/archdoc.md): the modules and how they depend on each other.
- [Developer notes](docs/devdoc.md): how to build and test, the conventions, the key design decisions and behaviour
  worth knowing.
- [Open points](docs/todo.md): what is missing, unfinished or undecided.

## License

Apache License 2.0, see [LICENSE](LICENSE).
