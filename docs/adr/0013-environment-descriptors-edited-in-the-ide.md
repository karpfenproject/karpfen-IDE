# 13. Environment descriptors are edited in the IDE with an IDE-owned JSON parser

- Status: Accepted
- Date: 2026-10-22
- Source: the owner's decision after phase 1 that environment descriptors must be editable in the IDE, as plain text
  or with a form or template; the [architecture proposal](../architecture-proposal.md) already calls the descriptor
  IDE-owned JSON

## Context

Environment descriptors (`*.kenv.json`) name the files of one runnable modeling context and how the runtime runs them.
In phase 1 the workspace read them, but the IDE opened them in another application, so a user could not edit them with
the language support the modeling languages have. A JSON library parses valid text only and loses the source position
of each value, while an editor needs a tree of every text, also of a broken one while the user types.

## Decision

- Environment descriptors are a fourth language of core-document, edited in the IDE's own editor as text, with
  highlighting, outline, folding, completion, hover, go to definition and the workspace's findings. A new descriptor is
  written from a template.
- The IDE reads descriptors with its own tolerant JSON parser, which keeps the position of every value and recovers
  from every error. karpfen-dsl-tools stays the language core of the modeling languages; JSON is not one of them.
- The typed `EnvironmentDescriptor` in core-document is the one declaration of the descriptor's shape. The schema that
  checks, highlighting and the language services read is derived from it, and the workspace decodes descriptors into
  it with kotlinx.serialization.

## Consequences

- core-document depends on kotlinx.serialization, which the core layer may use.
- A new descriptor key is one property of `EnvironmentDescriptor` with its annotations; the checks, highlighting,
  completion and hover follow from the derived schema.
- A form-based editor can be added later on top of the same typed descriptor and schema.
