# 19. The runtime runs in an IDE-owned Docker container or at a URL, never as a local process

- Status: Accepted; built in the fifth increment after phase 1; partially supersedes
  [the runtime decision](0006-runtime-in-ide-owned-docker-container.md)
- Date: 2026-11-06
- Source: the owner's decisions of 2026-11-06 on hosting the runtime

## Context

The runtime decision foresaw three hosts behind one interface: an IDE-owned Docker container, a runtime attached by
URL, and the runtime's distribution run as a local process with the host's JDK and Python, which needs a preflight of
Python and its packages and a third lifecycle. It also expected a runtime image published from the runtime
repository, which the IDE would pull. No such image exists yet, and the IDE needs the runtime's branch with IDE
support.

## Decision

- There are exactly two hosts: the IDE-owned Docker container, the default, and a runtime started elsewhere that the
  IDE attaches to by URL. The IDE never runs the runtime as a local Java process, now or later.
- Until a published runtime image exists, the IDE builds the image on first use from the karpfen-runtime sources
  bundled with it: the build packs the files git tracks in the karpfen-runtime submodule into a deterministic archive
  among the resources of runtime-host, and the Docker host builds the image from it through the Docker API, tagged by
  the archive's content hash, with its progress shown. The first build takes minutes; later starts reuse the image.
- The Docker host runs one container per workspace, publishes the runtime on `127.0.0.1` at an ephemeral port and
  copies the runtime's `application.conf` into the container before it starts. Every image and container it makes
  carries the label `io.karpfen.ide.runtime=true`, and it only lists, stops or removes what carries that label, and
  containers only of its own workspace. A running container built from the current sources is adopted; every other
  container of the workspace is removed.
- A runtime at a URL is checked for its health and its IDE support; the IDE never stops it.
- When the IDE exits, it shuts down every session and stops its container within 15 seconds.

## Consequences

- runtime-host holds both hosts behind `RuntimeHost` and `RuntimeHostFactory`; only its Docker engine adapter uses
  docker-java, which the architecture tests enforce.
- The runtime the IDE builds is the submodule's commit. Moving the submodule changes the content hash and so the image;
  after a build, older images of the IDE that no container uses are removed.
- The hosts are tested against a fake Docker engine that answers only what each test programs. The tests against the
  local Docker engine carry their own tag and run only in their own task; they remove their containers and keep the
  IDE's image, which costs minutes to build.
- Once the runtime repository publishes an image, pulling it instead of building it is a new decision.
- The [README](../../README.md) says how to run the IDE against Docker or a URL and how to remove the IDE's runtime
  images and containers by their label; the [codebase guide](../codebase-guide.md) maps the hosts.
