# 11. Time-budget tests are written but not run

- Status: Accepted
- Date: 2026-10-22
- Source: the owner's decision after phase 1 ("do no time budget testing for now … exclude them in all test runs;
  performance tuning is a task for later"); the [phase-1 specification](../history/phase-1/p1-text-editing-spec.md)
  had asked for asserted budgets for the document session, the editor view and the definition of done

## Context

Phase 1 added tests that assert wall-clock durations against budgets. Wall-clock time on a build machine is unreliable,
and one of these tests failed under load although the code had not changed.

## Decision

- Tests that compare a wall-clock duration with a limit carry the JUnit tag `budget`, and every test task excludes
  it, those of build-logic included, so no build, check or CI run executes them. There is no opt-in switch: enabling
  them is part of the later performance work.
- Functional assertions that lived inside a budget test move to untagged tests, which keep running.
- An architecture test catches a timing test without the tag, in the modules and in build-logic, and a test task that
  does not exclude it.

The KDoc of `TestTags.BUDGET` in build-logic explains the rule, its reasons and what it leaves undetected; the KDoc of
`BudgetTestTaggingTest`, `WallClockAssertions`, `MeasuredValues` and `TimeLimits` in architecture-tests says what
counts as a timing test.

## Consequences

- Builds no longer fail on machine load, and the budget tests stay in the code as documentation of the performance
  targets.
- Performance regressions are not detected automatically until the performance work re-enables or replaces the
  budget tests.
