# 11. Time-budget tests are written but not run

- Status: Accepted
- Date: 2026-10-22
- Source: owner decision after phase 1 ("do no time budget testing for now … exclude them in all test runs;
  performance tuning is a task for later"); spec §3.4, §7.2 and §11 item 4 asked for asserted budgets

## Context

Phase 1 added tests that assert wall-clock durations against budgets with 5x slack (tokenize, parse, language
services, text operations, find, editor frames, the project tree). Wall-clock time depends on machine load (the build
runs many test tasks in parallel), garbage collection, CPU throttling and, on the owner's machine, sleep/wake cycles that
make the clock unreliable. One of them failed under load although the code had not changed.

## Decision

- Tests that compare a wall-clock duration with a limit carry the JUnit tag `budget` (`@Tag("budget")` on the class or
  on the test functions; `io.karpfen.ide.buildlogic.TestTags.BUDGET` in build-logic).
- The `karpfen.kotlin-jvm` convention excludes that tag from every `Test` task, so no build, check or CI run executes
  them. There is no opt-in switch: enabling them is part of the later performance task.
- Functional assertions that used to live inside a budget test move to untagged tests (e.g. the tree view's laziness,
  the synthetic document parsing without syntax errors).
- `BudgetTestTaggingTest` (architecture-tests) is a tripwire: test code that reads the wall clock and names a budget,
  slack or guard limit must carry the tag.

## Consequences

- Builds no longer fail on machine load; budget tests remain as executable documentation for performance work.
- Performance regressions are not detected automatically until that task re-enables or replaces them (for example
  with a dedicated, serial benchmark task or with deterministic operation counts).
- Functional tests that wait for asynchronous events still use generous wall-clock timeouts (file watcher, folder
  chooser, the find search that gives up at its own time limit); they are not budget tests and keep running.
