# ITF-69 Workbench Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close stale file event streams promptly and give bare workspace-scoped URLs an accurate error.

**Architecture:** Recheck `UiSession.workspace()` after a file emitter enters `FileWatcher`'s map so either the subscription or the watcher observes a switch. Split absent workspace claims from mismatched claims in the existing MVC interceptor; keep reports behind it.

**Tech Stack:** Java 25, Spring MVC/SSE, JUnit 5, AssertJ, Maven, English and Spanish properties.

**Spec:** `docs/superpowers/specs/2026-11-02-itf-69-workbench-polish-design.md`

## Global Constraints

- Keep report lookup, token checks, and workspace-scoped path registration unchanged.
- Preserve `ui.workspace.switched` for present but nonmatching claims and `ui.workspace.none` when no workspace is open.
- Do not put secrets, file content, or report content in file events or new errors.

## Review Focus

1. Subscription for old workspace after watcher processed the switch: emitter completes at once (Task 1 test).
2. Subscription for current workspace: emitter remains usable (Task 1 test).
3. Bare report URL while a workspace is open: 409 `ui.workspace.missing`, no false switched wording (Task 2 test).
4. Missing workspace on another guarded endpoint in Spanish: same key, Spanish copy (Task 2 test).
5. A present but blank or malformed claim: retains `ui.workspace.switched`, so the new case is limited to absent claims (Task 2 test).

---

### Task 1: Close stale file event subscriptions

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/FileWatcher.java` (`subscribe(SseEmitter, Path)`)
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/ui/FileWatcherTest.java`

**Interfaces:**
- Consumes: `UiSession.workspace(): Path`; existing `FileWatcher.subscribe(SseEmitter, Path): void`.
- Produces: unchanged public `FileWatcher.subscribe(Path): SseEmitter` contract, with immediate completion for stale workspace.

- [ ] **Step 1: Write failing tests.** Add `testLateSubscriptionEndsImmediately`: start watcher on X, switch session to Y, wait for a first X emitter to complete (proving `endOthers(Y)` ran), subscribe a second X emitter, assert immediate completion. Also subscribe an emitter for Y and assert it has not completed after the initial comment.
- [ ] **Step 2: Verify failure.** Run `./mvnw test -pl idm-test-runner -am -Dtest=FileWatcherTest -Dsurefire.failIfNoSpecifiedTests=false`; expect the new immediate-completion assertion to fail.
- [ ] **Step 3: Implement.** In `FileWatcher.subscribe(SseEmitter emitter, Path workspace): void`, after `emitters.put`, compare `workspace` with `session.workspace()` using `Objects.equals`. On mismatch, remove this emitter, complete it, and return before sending `watching`. Keep existing callbacks.
- [ ] **Step 4: Verify pass.** Rerun the focused command; expect `FileWatcherTest` to pass.
- [ ] **Step 5: Commit.** Stage only the watcher and its test; commit `fix: close late file event subscriptions` with `ITF-69` in the body.

### Task 2: Explain missing workspace context

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/WorkspaceGuard.java` (`preHandle`)
- Modify: `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages.properties`
- Modify: `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages_es.properties`
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/ui/api/WorkspaceGuardTest.java`

**Interfaces:**
- Consumes: `WorkspaceGuard.claimed(HttpServletRequest): String`, returning null only for no claim after Task 2; `ApiError(HttpStatus, Message)`.
- Produces: `runner.ui.workspace.missing`, surfaced as JSON key `ui.workspace.missing` with HTTP 409; no API or path changes.

- [ ] **Step 1: Write failing HTTP tests.** Update `testMissingRefused` to assert key `ui.workspace.missing` for a bare report URL and `/api/files/tree`, and verify the English message below. Add a Spanish bare request asserting the Spanish message below. Add blank `workspace=` query and malformed encoded header assertions that retain `ui.workspace.switched`.
- [ ] **Step 2: Verify failure.** Run `./mvnw test -pl idm-test-runner -am -Dtest=WorkspaceGuardTest -Dsurefire.failIfNoSpecifiedTests=false`; expect the missing-key assertions to fail.
- [ ] **Step 3: Implement.** In `WorkspaceGuard.preHandle(HttpServletRequest, HttpServletResponse, Object): boolean`, throw 409 with `Message.of("runner.ui.workspace.missing")` when `claimed(request) == null`; leave present-claim comparison on the switched path. In `claimed`, return an empty string for a malformed encoded header so only an absent claim returns null. Add `runner.ui.workspace.missing=This link or request does not identify a workspace. Open it from the workbench and try again.` and `runner.ui.workspace.missing=Este enlace o solicitud no identifica un espacio de trabajo. Ábralo desde el banco de trabajo e inténtelo de nuevo.` to the respective translation files.
- [ ] **Step 4: Verify pass.** Rerun the focused command; expect `WorkspaceGuardTest` to pass. Run `./mvnw test -pl idm-test-runner -am`; expect Maven success, including the front-end and report HTTP tests.
- [ ] **Step 5: Commit.** Stage only the guard, translations, and test; commit `fix: explain missing workbench workspace` with `ITF-69` in the body.
