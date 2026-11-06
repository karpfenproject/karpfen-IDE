# runtime-host

The runtime of a workspace, as the IDE provides it before each deployment. There are two hosts, and only two: a Docker
container that the IDE builds, runs, watches and removes, one per workspace, and a runtime started elsewhere that the
IDE attaches to by URL. The IDE never runs the runtime as a local Java process.

## Map of the code

- `RuntimeHost.kt`: the API, `RuntimeHost` with its statuses, problems and log lines, and `RuntimeHostFactory`.
- `RuntimeHosts.kt`: `RuntimeHosts()`, the factory of the two hosts, and the internal options through which the tests
  hand in a fake engine, the fake runtime, their own sources and virtual time.
- `DockerRuntimeHost.kt`: the container of a workspace. It checks the engine, adopts a running container of the
  workspace built from the current sources or removes the workspace's other containers, builds the image when it is
  missing, creates the container with its configuration, starts it, waits for the runtime's health and IDE support,
  watches the container and removes it on stop.
- `UrlRuntimeHost.kt`: a runtime at a URL, checked once for its health and its IDE support.
- `HostLog.kt`: the log both hosts offer, which replays its last 2,000 lines.
- `bundled/`: `BundledRuntimeSources` reads the runtime sources that the build bundles as resources.
- `docker/`: the names and labels of the image and the container (`DockerNames.kt`), the runtime's
  `application.conf` in the container (`RuntimeConfiguration`) and the build progress for the status
  (`BuildProgress`).
- `docker/engine/`: the port `DockerEngine` through which the host talks to Docker, its adapter `DockerJavaEngine`, the
  only code that uses docker-java, and `DockerEndpointResolver`, which finds the local engine as the Docker command line
  does.

## The runtime image

The build task `bundleRuntimeSources` (build-logic's `BundleRuntimeSources`) packs the files git tracks in the
`karpfen-runtime` submodule, its nested `karpfen-dsl-tools` included, into a deterministic archive among the resources
of this module, with the archive's SHA-256, the runtime commit and the number of files. On the first start the Docker
host builds the image `karpfen-ide/runtime:<first 12 hex digits of the hash>` from that archive through the Docker API;
this takes minutes, later starts reuse the image. After a build, older images of the runtime that no container uses
are removed.

## Docker resources and their labels

Every image and container of the IDE's runtime carries the label `io.karpfen.ide.runtime=true`; a container also
carries its workspace (`io.karpfen.ide.workspace`, `io.karpfen.ide.workspace.hash`) and the hash of its sources. The
host only lists, stops or removes resources with that label, and containers only of its own workspace. The container
is named `karpfen-ide-runtime-<first 10 hex digits of the SHA-256 of the workspace path>` and publishes the runtime on
`127.0.0.1` at an ephemeral port.

To remove them by hand, the containers first:

```
docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)
docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)
```

A build leaves two kinds of images without the label, which the host never touches. The runtime's `Dockerfile` has a
build stage that compiles the runtime and a runtime stage, and the engine labels only the image of the last stage, so
every build normally leaves the build stage as an untagged image; the build also pulls the base images
`eclipse-temurin:21-jdk` and `debian:trixie-slim`. `docker images --filter dangling=true` lists the untagged images,
those of other builds as well; the ones whose creation time is that of a runtime build can go with `docker rmi <id>`,
and the base images with `docker rmi eclipse-temurin:21-jdk debian:trixie-slim` when nothing else builds on them.

## Tests

The tests run the hosts against a fake engine and the fake runtime of runtime-protocol's test fixtures on virtual time.
The tests tagged `live-docker` run only in the opt-in task `liveDockerTest`, never in `test` or `check`:

```
./gradlew :runtime:runtime-host:liveDockerTest
```

They need a local Docker engine. The engine test builds a tiny image `FROM scratch` labelled `io.karpfen.ide.test=true`
and removes it again; the host test builds the runtime image unless it exists, starts a container for a workspace in a
temporary folder, adopts it from a second host, stops it and removes every container it made. The runtime image stays,
since it is the IDE's own image; the commands above remove it.
