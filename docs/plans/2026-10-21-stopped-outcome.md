# Stopped Is Its Own Outcome Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scenario the user stopped is counted as "stopped", never as a setup problem, and the workbench's file tree keeps showing the result of the last run that finished.

**Architecture:** The scenario record already says `stopped: true`. The runner's run totals, the workbench's last-result reader (`LastRuns`) and the HTML report learn a separate "stopped" outcome; the last-result lookup skips stopped records so the tree falls back to the last finished run. The front end shows the new outcome in the run panel's end line, the finding panel's recent runs and the start page's last-run line.

**Tech Stack:** Java 25 (`idm-test-engine` report, `idm-test-runner`), React + TypeScript + Vitest (`idm-test-runner/src/main/frontend`).

**Spec:** ITF-63 (https://optima-cyber-dev.youtrack.cloud/issue/ITF-63). Small change; the design was agreed in chat on 2026-10-21 and is recorded in *Design* below.

## Design (agreed 2026-10-21)

- **File tree:** a stopped scenario keeps the badge of its last run that finished (✓ / amber ✕ / red ✕); with no finished run, no badge. Stopping never makes the tree look worse.
- **Run panel:** the stopped row stays "stopped" (!); unreached rows "not run"; the end line counts it on its own — e.g. "5 scenarios: 2 passed, 1 finding, 1 stopped, 1 not run" — never "setup problem". The interrupted step keeps "Stopped — cleanup did not run" and the skipped cleanup.
- **Calls made by Claude, accepted:** the finding panel's recent runs list a stopped run as "stopped" (!), which never counts for "used to pass" or as a failure; the HTML report labels a stopped scenario "Stopped" and counts stopped scenarios in their own tile; the run totals get a separate stopped count and the setup-problem count stops including stopped scenarios; the `idmtest run` console is unchanged (it has no Stop).

## Global Constraints

- A scenario is **stopped** exactly when its record has `stopped: true` (`ScenarioResult.stopped()`); that check comes before the finding/setup rule everywhere. A stopped scenario is never counted as passed, finding or setup problem.
- Outcome name in JSON/API: `"stopped"` (alongside `"passed"`, `"finding"`, `"setup"`).
- Report copy (engine catalogs): `report.kind.stopped` en `Stopped` / es `Detenido`; `report.tile.stopped` en `Stopped` / es `Detenidos`. The stopped badge and tile use the theme's warning colour, not the error red, and always carry the word.
- Front-end copy: count key `ui.result.stopped` en `{count} stopped` / es `{count} detenido(s)`, following the existing `ui.result.*` count keys' plural style; `ui.run.end` parts use it and the existing "not run" wording; history chip label `ui.run.state.stopped` (exists).
- Exit codes unchanged: a stopped run still exits 1.
- Engine stays Spring-free; CLI output unchanged; no secret in any response or UI text.
- Commit trailer: a blank line, `ITF-63`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Build: `./mvnw -B verify` green and the YAML corpus 0 FAILED.

## Review Focus

- A scenario stopped in its first-ever run: the tree shows no badge (not red, not "stopped") — pinned in Task 1 (`testStoppedFirstRunHasNoLastResult`).
- Stopped in the newest run but a finding two runs ago and passed in between: the tree shows the newest *finished* result (passed) — pinned in Task 1 (`testLastOutcomeSkipsStopped`).
- "Run only the ones that found something" must not pick up or drop a scenario because of a stopped run — covered by Task 1's last-outcome rule (the tree's `lastResult` drives that menu); pinned in Task 2 (`only-findings ignores a stopped run`).
- Old `results.json` files without a `stopped` field still read as before — pinned in Task 1 (`testOldResultsWithoutStoppedField`).
- The start page's last-run line for a run whose only non-pass was a stop: shows "1 stopped" with the warning badge, not red — pinned in Task 2 (`last run with a stop`).

---

### Task 1: Server and report — count "stopped" on its own

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/run/RunOutcome.java`, `run/RunService.java` (`Tally`)
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/LastRuns.java` (`Outcome`, `LastRun`, `lastOutcomes`, `history`, `scenarios`/`outline`)
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/RunSessions.java` (`RunFinished`)
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/result/HtmlReportWriter.java`; engine catalogs `messages(_es).properties`
- Test: `run/RunServiceTest.java`, `ui/LastRunsTest.java` (create if absent; otherwise the existing tests that cover LastRuns), `ui/api/RunsControllerTest.java` (runFinished payload), `HtmlReportWriterTest.java`

**Interfaces:**
- Produces:
  - `RunOutcome(String runId, Path folder, int total, int passed, int findings, int setupProblems, int stoppedScenarios, boolean stopped, int exitCode)` — `stoppedScenarios` counts scenarios with `stopped()`; they are not in `setupProblems`.
  - SSE `runFinished` gains `stoppedScenarios` (same name).
  - `LastRuns.Outcome` gains `STOPPED("stopped")`; `LastRun` gains `int stopped` (after `setupProblems`); `lastOutcomes` never returns `STOPPED` (it keeps looking at older runs for that scenario); `history(workspace, scenario, …)` lists a stopped run with `result: "stopped"`; the workspace-wide worst result ranks `setup > finding > stopped > passed`.
  - `GET /api/files/tree` `lastResult` therefore never says `"stopped"`.

- [ ] **Step 1: Failing tests.**
  - `RunServiceTest.testStoppedCountsOnItsOwn`: stopping during a wait (existing stop fixture) → `outcome.stoppedScenarios() == 1`, `setupProblems() == 0`, `stopped()` true, exit code 1.
  - `testLastOutcomeSkipsStopped`: three run folders for `a.yaml` — oldest finding, middle passed, newest stopped → `lastOutcomes` gives `PASSED`; `history` gives `["stopped","passed","finding"]` newest first.
  - `testStoppedFirstRunHasNoLastResult`: only one run, stopped → `lastOutcomes` has no entry for the scenario; the files tree node's `lastResult` is null.
  - `testOldResultsWithoutStoppedField`: a results.json written without any `stopped` key reads exactly as before (finding/setup/passed).
  - `LastRun` for a run with 2 passed + 1 stopped → `passed 2, findings 0, setupProblems 0, stopped 1`.
  - `RunsControllerTest`: the stop-during-wait run's `runFinished` event has `stoppedScenarios: 1`, `setupProblems: 0`.
  - `HtmlReportWriterTest`: a stopped scenario shows the `Stopped` badge (es `Detenido`) and no `Setup problem` badge; the tiles show `Stopped 1` and `Setup problems 0`.
- [ ] **Step 2:** run them → fail (`./mvnw -B test -pl idm-test-runner -am -Dtest='RunServiceTest,LastRunsTest,RunsControllerTest,HtmlReportWriterTest' -Dsurefire.failIfNoSpecifiedTests=false`).
- [ ] **Step 3: Implement.** Check `stopped` first in `Tally`, in `LastRuns.scenarios` (read the record's `stopped` boolean, missing = false) and in the report; keep `ScenarioResult.failureKind()` unchanged (the interrupted step stays SETUP in the JSON). Update every `RunOutcome`/`LastRun` constructor call and the CLI's `ConsoleProgress` compile-only (its printed text must not change).
- [ ] **Step 4:** focused tests pass; `./mvnw -B verify` green, corpus 0 FAILED.
- [ ] **Step 5: Commit** — `fix: a stopped scenario counts as stopped, not as a setup problem`.

### Task 2: Workbench front end and docs

**Files:**
- Modify: `idm-test-runner/src/main/frontend/src/run/runStore.ts`, `run/RunPanel.tsx` (end line), `run/FindingPanel.tsx` (history chips), `start/format.ts` (last-run line), `api/client.ts` types (`LastRun.stopped`, history result union), `i18n/en.json`, `i18n/es.json`
- Modify: `docs/guides/workbench.md` (remove "currently counts as a setup problem"; say how a stop is shown), `docs/reference/writing-scenarios.md` §9 (the ITF-63 sentence), `docs/architecture/capabilities.md` (Workbench row: drop "A stopped scenario counts as a setup problem")
- Test: `run/RunPanel.test.tsx`, `run/FindingPanel.test.tsx`, `start/StartPage.test.tsx`, `run/RunButton.test.tsx`

**Interfaces:**
- Consumes: Task 1's `runFinished.stoppedScenarios`, history `result: "stopped"`, `LastRun.stopped`; `lastResult` never `"stopped"`.
- Produces: `ScenarioState` gains `'stopped'` (a scenario whose `scenarioFinished` result has `stopped: true`); a stopped `scenarioFinished` never changes the tree badge.

- [ ] **Step 1: Failing tests** (fake event stream / fake fetch):
  - `end line counts stopped`: 5 scenarios — 2 passed, 1 finding, 1 stopped, 1 not reached → end line exactly `5 scenarios: 2 passed, 1 finding, 1 stopped, 1 not run` (es equivalent), no `setup problem` text, the stopped row has the `!` badge labelled "stopped".
  - `stopped keeps the tree badge`: a scenario whose tree `lastResult` is `passed` is stopped → after `scenarioFinished` its tree badge is still ✓ passed (the tree refresh returns `passed`).
  - `history shows a stopped run`: history `[stopped, passed]` → the chips read "stopped" (!) then "passed", and "used to pass" logic still finds the passed run; a history of only `stopped` shows no "used to pass".
  - `only-findings ignores a stopped run`: the tree has one `finding` and one scenario whose last finished result is `passed` (its newest run was stopped) → "Run only the ones that found something (1)".
  - `last run with a stop`: `LastRun {passed: 2, findings: 0, setupProblems: 0, stopped: 1}` → start page line contains `1 stopped` with the warning badge, not red.
- [ ] **Step 2:** run them → fail (vitest via the Maven-installed node).
- [ ] **Step 3: Implement**, with the copy from Global Constraints; replace the old trailing "· stopped" marker on the end line with the counted part.
- [ ] **Step 4:** tests and i18n parity pass; docs updated; `./mvnw -B verify` green, corpus 0 FAILED.
- [ ] **Step 5: Commit** — `feat: the workbench shows a stopped scenario as stopped and keeps its last finished result`.
