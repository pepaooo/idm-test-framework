# New and Duplicate Scenario Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The workbench can create a scenario file from the browser — **New scenario** from a short template, and **Duplicate** of an existing scenario — and opens it in the editor.

**Architecture:** One new server endpoint creates a file under `scenarios/` with given content and never overwrites. The front end adds a **New scenario** button next to "Add files" and a **Duplicate** action on scenario rows; both ask for a name, call the endpoint, refresh the tree and open the file.

**Tech Stack:** Java 25 (`idm-test-runner`), React + TypeScript + Vitest (`idm-test-runner/src/main/frontend`).

**Spec:** ITF-70 (https://optima-cyber-dev.youtrack.cloud/issue/ITF-70). Small change; the design was agreed in chat on 2026-10-24 and is recorded below.

## Design (agreed 2026-10-24)

- **New scenario:** a button next to "Add files" in the file tree. It asks for a file name (`.yaml` added if missing) and creates the file in the folder selected in the tree, else the open scenario's folder, else `scenarios/` — always under `scenarios/`. The file starts from a short template (a `name:` and `description:`, a `random-user` fixture, a commented `ldap.create` and `ldap.verify` step and a commented `cleanup:` block) that validates with no problems. It opens in the editor.
- **Duplicate:** an action on any scenario row (shown on hover and on the selected row). It asks for the new name, suggesting `<name>-copy.yaml` in the same folder, and opens the copy.
- **Both:** a name that already exists, is empty, or contains `/`, `\`, `..` or `:` is refused with a clear message; nothing is ever overwritten. Disabled in a read-only (second) tab. en/es; workbench guide updated.
- **Calls made by Claude, accepted:** Duplicate copies what the editor shows, including unsaved edits; the original file is not touched or saved. The copy is exact (the YAML's `name:` is not changed). A plain-text password in the original stays in the copy with the usual warning.

## Global Constraints

- Endpoint: `POST /api/files/new` body `{path, content}` → 201 `{path, version}`; `path` is workspace-relative and must resolve under `scenarios/` (reuse `WorkspaceFiles.underScenarios` and its path rules) and end in `.yaml`/`.yml`; the file is created with `CREATE_NEW` (never overwrite) → 409 `{key: "ui.file.exists", message}` when it exists; 400 `ui.file.badName` for an empty name or one containing `/`, `\`, `..` or `:` in the file-name part; other path problems keep the existing `ui.file.badPath`; `secrets.env` never; content ≤ 2 MB (`ui.file.tooLarge`); content written exactly as the UTF-8 bytes sent. Behind the workspace guard (`/api/files/**`). Missing parent folder under `scenarios/` → 400 `ui.file.badPath` (no folder creation in this ticket).
- Catalog copy (runner `runner.` + key, en/es): `ui.file.exists` en `{0} already exists; choose another name.` / es `{0} ya existe; elija otro nombre.`; `ui.file.badName` en `''{0}'' is not a file name: use letters, digits, spaces, - and _, without / \ .. or :.` / es `''{0}'' no es un nombre de archivo: use letras, dígitos, espacios, - y _, sin / \ .. ni :.`
- Front-end copy (`ui.tree.*`, en/es): `new` "New scenario"/"Nuevo escenario"; `duplicate` "Duplicate"/"Duplicar"; `newName` "Name of the new scenario"/"Nombre del nuevo escenario"; `duplicateName` "Name of the copy"/"Nombre de la copia"; `create` "Create"/"Crear"; `cancel` "Cancel"/"Cancelar".
- Template text (exact, in `scenarios` folder of the front end as a constant):
  ```yaml
  name: <file name without extension>
  description: Describe what this scenario checks.

  fixtures:
    user:
      generate: random-user

  steps:
  #  - ldap.create:
  #      dn: "cn=${user.userId},ou=users,o=data"
  #      as: createdDn
  #      attributes:
  #        cn: "${user.userId}"
  #        sn: "${user.lastName}"
  #  - ldap.verify:
  #      dn: "${createdDn}"
  #      exists: true

  #cleanup:
  #  - ldap.delete:
  #      dn: "${createdDn}"
  ```
  If `steps:` with no items fails validation, use `steps: []` with the commented example below it; the template must produce zero problems in the Problems panel (pinned by a test against the real validator).
- No secret in any response. Commit trailer: a blank line, `ITF-70`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Build: `./mvnw -B verify -pl idm-test-runner -am` green and the YAML corpus 0 FAILED (the full root build currently fails only a tripwire test set off by the user's uncommitted real scenarios).

## Review Focus

- Two tabs (or a double click) creating the same name at once: exactly one succeeds, the other gets 409 `ui.file.exists`, the first file's content is intact — pinned in Task 1 (`testCreateNeverOverwrites`).
- `../x.yaml`, `scenarios/../config/a.yaml`, `config/a.yaml`, `scenarios/a:b.yaml`, `scenarios/secrets.env`, a name without `.yaml`: all refused, nothing written — pinned in Task 1 (`testCreateRefusals`).
- The template validates with zero problems — pinned in Task 1 (`testTemplateValidates`, posting the front end's template text to `/api/validate`) and Task 2 uses the same constant.
- Duplicate of an open file with unsaved edits copies the edited text and leaves the original unsaved and unchanged on disk — pinned in Task 2 (`duplicate copies what the editor shows`).
- A refused name keeps the dialog open with the server's message and lets the user try another name — pinned in Task 2 (`refused name stays in the dialog`).

---

### Task 1: Server — create a scenario file

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/WorkspaceFiles.java` (new `create`), `ui/api/FilesController.java` (`POST /api/files/new`), runner catalogs
- Test: `ui/WorkspaceFilesTest.java`, `ui/api/FilesControllerTest.java`

**Interfaces:**
- Produces: `WorkspaceFiles.Content createScenario(String path, String content)` (returns content + version); `POST /api/files/new {path, content}` → 201 `{path, version}`.

- [ ] **Step 1: Failing tests** — `testCreateScenario` (201, file bytes exactly as sent, tree lists it), `testCreateNeverOverwrites` (8 threads, same name: one 201, the rest 409 `ui.file.exists`, file content from the winner), `testCreateRefusals` (the Review Focus list → 400 with `ui.file.badName` or `ui.file.badPath`, nothing written), `testTemplateValidates` (the template text from the constraints, with `<name>` replaced, posted to `/api/validate` → zero problems); en/es messages.
- [ ] **Step 2:** run them → fail (`./mvnw -B test -pl idm-test-runner -am -Dtest='WorkspaceFilesTest,FilesControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`).
- [ ] **Step 3: Implement** with `Files.write(..., CREATE_NEW)` inside the existing per-file lock; reuse the path resolution of `underScenarios` and the size/secrets rules of `add`/`save`.
- [ ] **Step 4:** pass; `./mvnw -B verify -pl idm-test-runner -am` green; corpus 0 FAILED.
- [ ] **Step 5: Commit** — `feat: the workbench can create a scenario file`.

### Task 2: File tree — New scenario and Duplicate

**Files:**
- Create: `idm-test-runner/src/main/frontend/src/workbench/NewScenarioDialog.tsx`, `workbench/scenarioTemplate.ts`
- Modify: `workbench/FileTree.tsx` (New scenario button, Duplicate row action), `workbench/Workbench.tsx` (target folder, open after create, editor text for Duplicate), `workbench/store.ts` only if needed to read a tab's current text, `api/client.ts` (if a helper is useful), `i18n/en.json`, `i18n/es.json`, `docs/guides/workbench.md`
- Test: `workbench/FileTree.test.tsx`, a new `workbench/newScenario.test.tsx`

**Interfaces:**
- Consumes: Task 1's `POST /api/files/new`; ITF-64's run-folder selection (`selectedFolder`) for the target folder.

- [ ] **Step 1: Failing tests** (fake server):
  - `new scenario in the selected folder`: folder `scenarios/users` selected → New scenario, type `disable-user` → POST `{path: 'scenarios/users/disable-user.yaml', content: <template with name disable-user>}`; the tree refreshes and the file opens in the editor.
  - Target fallback: no selection, open scenario in `scenarios/roles` → `scenarios/roles/`; nothing open → `scenarios/`.
  - `duplicate copies what the editor shows`: open `scenarios/a.yaml` with unsaved edits → Duplicate on its row, suggested name `a-copy.yaml`, Create → POST with the edited text; `a.yaml` stays unsaved in its tab and no PUT is sent; the copy opens.
  - `duplicate of a closed file`: copies its content from the server.
  - `refused name stays in the dialog`: 409 `ui.file.exists` → the server message shown, the dialog stays open, a second name succeeds.
  - Client checks before sending: empty name or `/ \ .. :` → the `badName` message without a request.
  - Read-only tab: both actions disabled. Spanish copy.
- [ ] **Step 2:** run them → fail (vitest via the Maven-installed node).
- [ ] **Step 3: Implement** with the copy and template from Global Constraints; the dialog is a small labelled form (Enter creates, Escape cancels, focus returns to the button).
- [ ] **Step 4:** tests and i18n parity pass; guide updated (creating and duplicating scenarios); `./mvnw -B verify -pl idm-test-runner -am` green; corpus 0 FAILED.
- [ ] **Step 5: Commit** — `feat: New scenario and Duplicate in the workbench's file tree`.
