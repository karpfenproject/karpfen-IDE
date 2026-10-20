# 7. Bridges are headless-first

- Status: Accepted
- Date: 2026-10-20
- Source: architecture proposal D6, §5.7, §9 item 5

## Context

Bridges connect the karpfen runtime to external systems such as MQTT brokers and ROS. They must run during
development inside the IDE and in deployment on a robot or edge box, where no IDE is present.

## Decision

A bridge is a plain Kotlin component with a lifecycle, runnable from a headless CLI (`bridge-runner`) without the
IDE; the IDE configures, hosts and monitors bridges during development. Message mapping is declarative first
(routes in `*.kbridge.json`), with a Kotlin SPI for custom codecs and transforms. A scripting hook comes later.
MQTT uses the HiveMQ MQTT client; ROS uses the rosbridge protocol over the Ktor WebSocket client.

## Consequences

- `bridges/*` must not depend on UI, notation, app or tools code; of the runtime they may only use
  `runtime-protocol` (enforced by the module dependency check and architecture tests).
- Only `bridge-mqtt` may use the HiveMQ client.
- Flow-control policies per route are needed because the engine is tick-based and events have a TTL.
