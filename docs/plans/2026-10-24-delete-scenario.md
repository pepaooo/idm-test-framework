# Delete a Scenario Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scenario file can be deleted from the workbench's file tree, after a confirmation that warns when the file cannot be recovered from git.

**Architecture:** Two server endpoints: a read-only git state for one file, and a versioned delete that refuses files outside `scenarios/`, a stale version, and a scenario in the active run. The front end adds a Delete row action with a confirmation, and closes the file's tab and draft on a delete (its own or one from another tab via the file watcher).

**Tech Stack:** Java 25 (`idm-test-runner`), React + TypeScript + Vitest (`idm-test-runner/src/main/frontend`).

**Spec:** ITF-71 (https://optima-cyber-dev.youtrack.cloud/issue/ITF-71). Small change; the design was agreed in chat on 2026-10-24 and is recorded below.

## Design (agreed 2026-10-24)

- A **Delete** action on each scenario row (next to Duplicate). It always asks first: "Delete users/disable-user.yaml? This cannot be undone in the workbench." — and says so if the file has unsaved edits.
- The file is removed from disk (not to the OS trash). Its tab closes, its browser draft is removed, the tree updates; other tabs that have it open close it too.
- Only files under `scenarios/` can be deleted; `config/`, `secrets.env` and other files do not offer it. Folders are out of scope.
- Refused: while a run that includes this scenario is going; when the file changed on disk since it was read (the user re-checks first); in a read-only (second) tab.
- Recovery is through git (the user's answer). **Call made by Claude, accepted:** when the workspace is a git repository and the file is untracked or has changes since its last commit, the confirmation adds: "This file is not in git yet, so it cannot be recovered." No undo button in this version.
- **Note (Task 2 ruling):** an outside delete (another program or another tab) keeps a tab's unsaved edits and drafts and offers Keep mine (create the file again) / Discard; only this tab's own confirmed delete drops them.

## Global Constraints

- `GET /api/files/git?path=` → `{state: "clean" | "changed" | "untracked" | "none"}` — `none` when the workspace has no `.git`, git is not installed, or git fails; read-only (no side effects); runs the installed git through the existing `GitTool` (no shell, inherited `GIT_*` variables stripped, a timeout), e.g. `git status --porcelain=v1 -- <path>` plus `git ls-files --error-unmatch -- <path>`; never writes anything.
- `DELETE /api/files?path=&version=` → 204; `version` required (400 `ui.request.invalid`); 409 `{key: "ui.file.changed", message, currentVersion}` when the file changed; 409 `{key: "ui.file.inRun", message}` when the active run includes this scenario (before the run's scenario list is known — while it is starting — any scenario delete is refused with the same key); 404 `ui.file.notFound`; 400 `ui.file.notDeletable` for anything not a `.yaml`/`.yml` file under `scenarios/`; 403 `ui.file.secrets` for `secrets.env`. Path rules through `WorkspaceFiles` (no escape, real path checks); the delete happens under the per-file lock after the version check. Both endpoints behind the workspace guard (`/api/files/**`).
- Catalog copy (runner `runner.` + key, en/es): `ui.file.inRun` en `{0} is part of the run that is going; wait for it to finish or stop it.` / es `{0} forma parte de la ejecución en curso; espere a que termine o deténgala.`; `ui.file.notDeletable` en `only scenario files under scenarios/ can be deleted.` / es `solo se pueden eliminar archivos de escenario de scenarios/.`
- Front-end copy (en/es): `ui.tree.delete` "Delete"/"Eliminar"; `ui.tree.deleteConfirm` "Delete {path}? This cannot be undone in the workbench." / "¿Eliminar {path}? No se puede deshacer en el workbench."; `ui.tree.deleteUnsaved` "It has unsaved edits, which will be lost." / "Tiene cambios sin guardar, que se perderán."; `ui.tree.deleteNotInGit` "This file is not in git yet, so it cannot be recovered." / "Este archivo aún no está en git, así que no se podrá recuperar."
- No secret in any response. Commit trailer: a blank line, `ITF-71`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Build: `./mvnw -B verify -pl idm-test-runner -am` green and the YAML corpus 0 FAILED (the full root build currently fails only a tripwire test set off by the user's uncommitted real scenarios).

## Review Focus

- Delete with a stale version (the file was edited on disk after opening): 409 `ui.file.changed`, file kept — pinned in Task 1 (`testDeleteStaleVersionRefused`).
- Deleting a scenario of the run that is going: 409 `ui.file.inRun`, file kept; a scenario not in that run can be deleted — pinned in Task 1 (`testDeleteRefusedDuringItsRun`).
- `config/application-lab.yml`, `secrets.env`, `scenarios/` itself, `../x.yaml`, a symlink in `scenarios/` pointing outside it: refused, nothing deleted — pinned in Task 1 (`testDeleteRefusals`).
- Git state of a committed clean file, a modified file, an untracked file, and a workspace without `.git` (and git missing): `clean`/`changed`/`untracked`/`none`, and the call changes nothing in the repository — pinned in Task 1 (`testGitState`, skipped where git is not installed except the `none` case).
- Another tab has the file open when it is deleted: that tab's editor closes it, its draft is removed, no "changed on disk" dialog appears — pinned in Task 2 (`delete from another tab closes the file`).

---

### Task 1: Server — git state and versioned delete

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/WorkspaceFiles.java` (delete), `ui/GitTool.java` (file state), `ui/RunSessions.java` (whether the active run includes a scenario), `ui/api/FilesController.java` (two endpoints), runner catalogs
- Test: `ui/WorkspaceFilesTest.java`, `ui/GitToolTest.java`, `ui/api/FilesControllerTest.java`

**Interfaces:**
- Produces: `GitTool.FileState state(Path workspace, String relativePath)` with `enum FileState {CLEAN, CHANGED, UNTRACKED, NONE}`; `WorkspaceFiles.delete(String path, String version)`; `RunSessions.includes(String scenarioPath)` (true while starting); `GET /api/files/git?path=`, `DELETE /api/files?path=&version=`.

- [ ] **Step 1: Failing tests** — the four Task 1 Review Focus lines plus: a plain delete → 204, file gone, the watcher emits `deleted`; `version` missing → 400; not found → 404; en/es messages.
- [ ] **Step 2:** run them → fail (`./mvnw -B test -pl idm-test-runner -am -Dtest='WorkspaceFilesTest,GitToolTest,FilesControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`).
- [ ] **Step 3: Implement.** RunSessions keeps the run's scenario names from its `runStarted`; `includes` compares workspace-relative paths.
- [ ] **Step 4:** pass; `./mvnw -B verify -pl idm-test-runner -am` green; corpus 0 FAILED.
- [ ] **Step 5: Commit** — `feat: the workbench can delete a scenario file and tell its git state`.

### Task 2: File tree — the Delete action

**Files:**
- Modify: `idm-test-runner/src/main/frontend/src/workbench/FileTree.tsx` (Delete row action), `workbench/Workbench.tsx`, `workbench/store.ts` (close a deleted file's tab and remove its draft, for its own delete and for a `deleted` file event), `i18n/en.json`, `i18n/es.json`, `docs/guides/workbench.md`
- Create: `workbench/DeleteDialog.tsx` (a confirmation dialog; reuse the NewScenarioDialog's patterns)
- Test: `workbench/FileTree.test.tsx`, a new `workbench/deleteScenario.test.tsx`

**Interfaces:**
- Consumes: Task 1's endpoints.

- [ ] **Step 1: Failing tests** (fake server):
  - `delete asks first and removes the file`: Delete on `scenarios/users/a.yaml` → the dialog shows `Delete scenarios/users/a.yaml? This cannot be undone in the workbench.`; Cancel sends nothing; Delete sends `DELETE /api/files?path=…&version=<the version read>`; the tab closes, the draft is removed, the tree updates.
  - `unsaved edits are mentioned`: an open file with unsaved edits → the dialog adds the unsaved line; on delete the draft is removed.
  - `not in git is mentioned`: git state `untracked` or `changed` → the not-in-git line; `clean` and `none` → no such line.
  - `delete from another tab closes the file`: a `deleted` file event for an open file → its tab closes, its draft is removed, no changed-on-disk dialog.
  - `refusals are shown`: 409 `ui.file.inRun` and 409 `ui.file.changed` → the server's message in the dialog, the file stays; after `ui.file.changed` the editor offers the existing changed-on-disk choices.
  - Delete is offered only on scenario rows (not `config/`, `secrets.env`, folders); disabled in a read-only tab; Spanish copy.
- [ ] **Step 2:** run them → fail (vitest via the Maven-installed node).
- [ ] **Step 3: Implement** with the copy from Global Constraints; the dialog asks for the git state when it opens (shows the confirmation without the git line until the answer arrives, never blocks Delete on it).
- [ ] **Step 4:** tests and i18n parity pass; guide updated (deleting a scenario, recovery through git); `./mvnw -B verify -pl idm-test-runner -am` green; corpus 0 FAILED.
- [ ] **Step 5: Commit** — `feat: delete a scenario from the workbench's file tree`.
