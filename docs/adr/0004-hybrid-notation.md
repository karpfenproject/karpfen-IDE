# 4. Hybrid notation: graph structure is graphical, leaves are textual

- Status: Accepted
- Date: 2026-10-20
- Source: the architecture proposal's [hybrid notation decision](../architecture-proposal.md#hybrid-notation-graph-structure-is-graphical-leaves-are-textual) and its
  [notation layer](../architecture-proposal.md#notation-layer-notation-diagram-ui-diagram)

## Context

Blended modeling is a goal: users should work textually and graphically on the same models. KStates mixes graph
structure (states, nesting, transitions, SPLIT/JOIN) with textual leaves (`ENTRY`/`DO` blocks, conditions,
macro bodies, embedded Python).

## Decision

Graph structure is drawn and manipulated graphically; leaves are embedded text cells that use the same editor
component as the text view (highlighted and validated).

KStates: nested states, prioritized transitions, fork/join bars, `ENTRY`/`DO` cells, macros in a side table.
KMeta: class diagram. KModel: object diagram / containment tree, which doubles as the live visualizer surface.

## Consequences

- The code editor component must be embeddable in diagram cells (see
  [the UI toolkit decision](0005-compose-multiplatform-for-desktop.md)).
- The notation layer (`notation/*`) models nodes, compartments, text cells and edges independently of the UI.
- Auto-layout uses ELK behind an isolated adapter so a custom layouter can replace it.
