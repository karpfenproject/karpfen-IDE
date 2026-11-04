#!/bin/bash
# Copyright 2026 Karl Kegel
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Usage: runtime/live-runtime.sh <command> [arguments]
#
# Runs the karpfen runtime with IDE support in throwaway Docker containers for the live tests. Every container, volume
# and image it makes carries the label io.karpfen.ide.test=true; it never touches anything else.
#
#   build               Check the local Docker engine and the runtime checkout, then build the test image.
#   up                  Build, start one runtime, wait until it is healthy, print its URL, run id and how to remove it.
#   test [gradle args]  Remove orphans of this checkout, build, start a runtime, run the live tests of both runtime
#                       modules (or the given Gradle arguments) against it, tear it down and exit with Gradle's status.
#   down [run id]       Tear down one run of this checkout, or all of them.
#   status              List the containers, volumes and images of the harness.
#   cleanup             Remove every container, volume and image of the harness, verify, and report what stays.
#
# Environment: KARPFEN_RUNTIME_DIR (the runtime checkout to build, default <repository>/karpfen-runtime),
# KARPFEN_LIVE_HEALTH_TIMEOUT_S (default 180), KARPFEN_LIVE_TICK_DELAY_MS (default 50), KARPFEN_LIVE_EVENT_TTL_MS
# (default 3000), KARPFEN_LIVE_TRACING (default true). Run folders and build logs go to build/live-runtime/.

set -euo pipefail

LABEL="io.karpfen.ide.test"
REPOSITORY_NAME="karpfen-ide-test/karpfen-runtime"
CONTAINER_PREFIX="karpfen-ide-test-"
REPOSITORY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${KARPFEN_RUNTIME_DIR:-$REPOSITORY/karpfen-runtime}"
STATE_DIR="$REPOSITORY/build/live-runtime"
HEALTH_TIMEOUT_S="${KARPFEN_LIVE_HEALTH_TIMEOUT_S:-180}"
IMAGE=""
SOURCE=""
RUN_ID=""
RUN_DIR=""
CONTAINER=""
URL=""
STARTED_AT=""
GRADLE_STATUS="not run"
STILL_RUNNING="unknown"

fail() {
    echo "live-runtime: $*" >&2
    exit 1
}

require_local_docker() {
    command -v docker >/dev/null 2>&1 || fail "docker is not installed; install Docker Desktop or a local Docker engine"
    command -v git >/dev/null 2>&1 || fail "git is not installed"
    command -v curl >/dev/null 2>&1 || fail "curl is not installed"
    local endpoint="${DOCKER_HOST:-}"
    if [ -z "$endpoint" ]; then
        endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" ||
            fail "cannot read the current Docker context; start Docker Desktop or a local Docker engine"
    fi
    case "$endpoint" in
        unix://* | npipe://*) ;;
        *) fail "the Docker endpoint $endpoint is not local; use a local engine (unix:// or npipe://), a remote one would publish the runtime elsewhere" ;;
    esac
    docker info >/dev/null 2>&1 || fail "the Docker engine at $endpoint does not answer; start Docker Desktop or the local engine"
}

require_runtime_checkout() {
    [ -f "$RUNTIME_DIR/Dockerfile" ] || fail "no runtime checkout with a Dockerfile at $RUNTIME_DIR; set KARPFEN_RUNTIME_DIR"
    [ -f "$RUNTIME_DIR/karpfen-dsl-tools/settings.gradle" ] ||
        fail "the nested dsl-tools of $RUNTIME_DIR is not checked out; run git -C karpfen-runtime submodule update --init"
}

source_id() {
    local head
    head="$(git -C "$RUNTIME_DIR" rev-parse --short=12 HEAD)"
    if [ -n "$(git --no-optional-locks -C "$RUNTIME_DIR" status --porcelain --untracked-files=normal)" ]; then
        echo "$head-dirty"
    else
        echo "$head"
    fi
}

build_image() {
    SOURCE="$(source_id)"
    IMAGE="$REPOSITORY_NAME:$SOURCE"
    local commit log
    commit="$(git -C "$RUNTIME_DIR" rev-parse HEAD)"
    mkdir -p "$STATE_DIR/builds"
    log="$STATE_DIR/builds/$(date -u +%Y%m%dT%H%M%SZ)-$SOURCE.log"
    echo "Building $IMAGE from $RUNTIME_DIR (log: $log)"
    if ! DOCKER_BUILDKIT=1 docker build --file "$RUNTIME_DIR/Dockerfile" --tag "$IMAGE" \
        --label "$LABEL=true" --label "$LABEL.source=$SOURCE" --label "org.opencontainers.image.revision=$commit" \
        --progress=plain "$RUNTIME_DIR" >"$log" 2>&1; then
        tail -n 40 "$log" >&2
        fail "the image build failed; the whole log is $log"
    fi
    [ "$(docker image inspect --format "{{ index .Config.Labels \"$LABEL\" }}" "$IMAGE" 2>/dev/null)" = "true" ] ||
        fail "$IMAGE is not in the local image store with the label $LABEL=true; the active builder did not load it"
    local dangling id
    dangling="$(docker images --quiet --filter dangling=true --filter "label=$LABEL=true")"
    for id in $dangling; do
        echo "Removing the dangling test image $id"
        docker rmi "$id" >/dev/null 2>&1 || echo "Keeping the dangling test image $id, a container still uses it"
    done
}

new_run() {
    RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
    RUN_DIR="$STATE_DIR/runs/$RUN_ID"
    CONTAINER="$CONTAINER_PREFIX$RUN_ID"
    STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    mkdir -p "$RUN_DIR"
}

write_config() {
    local tracing="${KARPFEN_LIVE_TRACING:-true}"
    cat >"$RUN_DIR/application.conf" <<EOF
server {
  host = "0.0.0.0"
  port = 8080
}
websocket {
  queueTimeoutMs = 1000
  enabled = true
}
logging {
  level = "INFO"
  consoleOutput = true
}
engine {
  defaultTickDelayMs = ${KARPFEN_LIVE_TICK_DELAY_MS:-50}
  defaultEventTtlMs = ${KARPFEN_LIVE_EVENT_TTL_MS:-3000}
  eventConsumptionOnFire = true
}
engineTracing {
  tracingEnabled = $tracing
  tracingLogDirectory = "/app/logs"
  tracingConsoleOutput = false
  simpleTrace = true
}
EOF
}

start_container() {
    local mode="$1" port
    write_config
    docker create --name "$CONTAINER" \
        --label "$LABEL=true" --label "$LABEL.run=$RUN_ID" --label "$LABEL.mode=$mode" --label "$LABEL.pid=$$" \
        --label "$LABEL.workspace=$REPOSITORY" \
        --publish 127.0.0.1::8080 \
        --mount "type=volume,destination=/app/logs,volume-label=$LABEL=true,volume-label=$LABEL.run=$RUN_ID" \
        --stop-timeout 10 "$IMAGE" >/dev/null
    docker cp "$RUN_DIR/application.conf" "$CONTAINER:/app/application.conf"
    docker start "$CONTAINER" >/dev/null
    port="$(docker port "$CONTAINER" 8080/tcp | head -n 1)"
    port="${port##*:}"
    case "$port" in
        '' | *[!0-9]*) fail "cannot read the published port of $CONTAINER" ;;
    esac
    URL="http://127.0.0.1:$port"
    echo "Started run $RUN_ID: $CONTAINER at $URL"
}

wait_healthy() {
    local waited=0 state
    while [ "$waited" -lt "$HEALTH_TIMEOUT_S" ]; do
        state="$(docker inspect --format '{{.State.Status}}' "$CONTAINER" 2>/dev/null || echo missing)"
        [ "$state" = "running" ] || fail "$CONTAINER is $state; its log is written to $RUN_DIR/runtime.log at teardown"
        if curl --silent --fail --max-time 5 "$URL/health" >/dev/null 2>&1; then
            echo "The runtime at $URL is healthy"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    fail "$CONTAINER did not answer $URL/health within $HEALTH_TIMEOUT_S s; its log is written to $RUN_DIR/runtime.log at teardown"
}

teardown() {
    local run="$1" dir="$STATE_DIR/runs/$1" id name left
    set +e
    mkdir -p "$dir/traces"
    docker ps --all --filter "label=$LABEL=true" --filter "label=$LABEL.run=$run" --format '{{.ID}} {{.Names}}' | while read -r id name; do
        case "$name" in
            "$CONTAINER_PREFIX"*) ;;
            *) echo "Skipping $name: it carries the run label but not the harness name" >&2 && continue ;;
        esac
        docker stop --time 10 "$id" >/dev/null 2>&1
        docker logs --timestamps "$id" >"$dir/runtime.log" 2>&1
        docker cp "$id:/app/logs/." "$dir/traces/" >/dev/null 2>&1
        docker rm --force --volumes "$id" >/dev/null 2>&1
    done
    for id in $(docker volume ls --quiet --filter "label=$LABEL=true" --filter "label=$LABEL.run=$run"); do
        docker volume rm "$id" >/dev/null 2>&1
    done
    if [ "$run" = "$RUN_ID" ]; then
        printf 'run: %s\nimage: %s\nsource: %s\nurl: %s\nstarted: %s\nended: %s\ngradle exit status: %s\ncontainer still running after the tests: %s\n' \
            "$RUN_ID" "$IMAGE" "$SOURCE" "$URL" "$STARTED_AT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$GRADLE_STATUS" "$STILL_RUNNING" >"$dir/run.txt"
    fi
    left="$(docker ps --all --quiet --filter "label=$LABEL.run=$run")$(docker volume ls --quiet --filter "label=$LABEL.run=$run")"
    set -e
    [ -z "$left" ] || { echo "live-runtime: run $run left containers or volumes behind: $left" >&2; return 1; }
    echo "Run $run is torn down; its logs are in $dir"
}

remove_orphans() {
    local id run mode pid
    docker ps --all --filter "label=$LABEL=true" --filter "label=$LABEL.workspace=$REPOSITORY" \
        --format "{{.ID}} {{.Label \"$LABEL.run\"}} {{.Label \"$LABEL.mode\"}} {{.Label \"$LABEL.pid\"}}" |
        while read -r id run mode pid; do
            if [ "$mode" = "test" ] && ! ps -p "$pid" >/dev/null 2>&1; then
                echo "Removing the orphaned run $run: its script ($pid) is gone"
                teardown "$run"
            else
                echo "Keeping run $run ($mode, script $pid still running)"
            fi
        done
}

command_build() {
    require_local_docker
    require_runtime_checkout
    build_image
    echo "Built $IMAGE"
}

command_up() {
    require_local_docker
    require_runtime_checkout
    build_image
    new_run
    trap 'teardown "$RUN_ID"' EXIT
    trap 'exit 130' INT TERM HUP
    start_container up
    wait_healthy
    trap - EXIT INT TERM HUP
    echo "Run id:    $RUN_ID"
    echo "URL:       $URL"
    echo "Gradle:    -Pkarpfen.runtime.url=$URL -Pkarpfen.runtime.logs=$RUN_DIR"
    echo "Logs:      docker logs --follow $CONTAINER"
    echo "Remove it: runtime/live-runtime.sh down $RUN_ID"
}

command_test() {
    require_local_docker
    require_runtime_checkout
    remove_orphans
    build_image
    new_run
    trap 'teardown "$RUN_ID" || exit 1' EXIT
    trap 'exit 130' INT TERM HUP
    start_container test
    wait_healthy
    local tasks status=0
    if [ "$#" -gt 0 ]; then
        "$REPOSITORY/gradlew" -p "$REPOSITORY" --continue "$@" \
            "-Pkarpfen.runtime.url=$URL" "-Pkarpfen.runtime.logs=$RUN_DIR" || status=$?
    else
        tasks=":runtime:runtime-protocol:liveRuntimeTest :runtime:runtime-session:liveRuntimeTest"
        "$REPOSITORY/gradlew" -p "$REPOSITORY" --continue $tasks \
            "-Pkarpfen.runtime.url=$URL" "-Pkarpfen.runtime.logs=$RUN_DIR" || status=$?
    fi
    GRADLE_STATUS="$status"
    if [ "$(docker inspect --format '{{.State.Status}}' "$CONTAINER" 2>/dev/null)" = "running" ]; then
        STILL_RUNNING="yes"
    else
        STILL_RUNNING="no"
        echo "live-runtime: the runtime container was no longer running when the tests ended; see $RUN_DIR/runtime.log" >&2
    fi
    exit "$status"
}

command_down() {
    local run="${1:-}" runs
    require_local_docker
    if [ -n "$run" ]; then
        [ -n "$(docker ps --all --quiet --filter "label=$LABEL=true" --filter "label=$LABEL.run=$run" \
            --filter "label=$LABEL.workspace=$REPOSITORY")" ] || fail "no run $run of this checkout"
        teardown "$run"
        return
    fi
    runs="$(docker ps --all --filter "label=$LABEL=true" --filter "label=$LABEL.workspace=$REPOSITORY" \
        --format "{{.Label \"$LABEL.run\"}}" | sort -u)"
    for run in $runs; do
        teardown "$run"
    done
}

command_status() {
    require_local_docker
    echo "Containers:"
    docker ps --all --filter "label=$LABEL=true" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}\t{{.Label \"$LABEL.run\"}}\t{{.Label \"$LABEL.mode\"}}\t{{.Label \"$LABEL.workspace\"}}"
    echo "Volumes:"
    docker volume ls --filter "label=$LABEL=true"
    echo "Images:"
    docker images --all --filter "label=$LABEL=true"
}

command_cleanup() {
    local id name reference left
    require_local_docker
    echo "Removing every container, volume and image labelled $LABEL=true:"
    command_status
    docker ps --all --filter "label=$LABEL=true" --format '{{.ID}} {{.Names}}' | while read -r id name; do
        case "$name" in
            "$CONTAINER_PREFIX"*) docker rm --force --volumes "$id" >/dev/null ;;
        esac
    done
    for id in $(docker volume ls --quiet --filter "label=$LABEL=true"); do
        docker volume rm "$id" >/dev/null
    done
    docker images --filter "label=$LABEL=true" --format '{{.Repository}}:{{.Tag}} {{.ID}}' | while read -r reference id; do
        case "$reference" in
            "$REPOSITORY_NAME":*) docker rmi "$reference" >/dev/null ;;
            "<none>:<none>") docker rmi "$id" >/dev/null ;;
        esac
    done
    left="$(docker ps --all --quiet --filter "label=$LABEL=true")$(docker volume ls --quiet --filter "label=$LABEL=true")"
    left="$left$(docker images --all --quiet --filter "label=$LABEL=true")"
    [ -z "$left" ] || fail "containers, volumes or images with $LABEL=true are left: $left"
    echo "Nothing labelled $LABEL=true is left."
    echo "Left alone: the base images eclipse-temurin:21-jdk and debian:trixie-slim and BuildKit's build cache"
    echo "(docker buildx du shows it); they carry no label of the harness and may be shared with other projects."
}

main() {
    local command="${1:-}"
    [ "$#" -gt 0 ] && shift
    case "$command" in
        build) command_build ;;
        up) command_up ;;
        test) command_test "$@" ;;
        down) command_down "$@" ;;
        status) command_status ;;
        cleanup) command_cleanup ;;
        *) sed -n '/^# Usage:/,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}" >&2 && exit 2 ;;
    esac
}

main "$@"
