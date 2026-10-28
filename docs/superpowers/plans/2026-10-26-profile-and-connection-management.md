# Profile and Connection Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let people create a commented starter profile, navigate many Lab connections, edit profile-specific display labels, and safely delete unused profile-owned connections.

**Architecture:** Keep the existing React workbench and Java Lab API. The top bar creates a profile file through a dedicated endpoint; the Lab view exposes label and deletion provenance; a bounded connection navigator replaces wrapping tabs; deletion rechecks scenario dependencies on the server before changing YAML. Existing runner identifiers and shared-settings precedence stay intact.

**Tech Stack:** Java 21, Spring Boot, SnakeYAML, JUnit 5, React, TypeScript, Vitest, Testing Library, Vite.

**Spec:** `docs/superpowers/specs/2026-10-26-profile-and-connection-management-design.md`

## Global Constraints

- New profile files are `config/application-<name>.yml`, created from the existing fully commented starter template. No copied values or secrets. Shared `config/application.yml` remains effective.
- Labels are optional profile-specific `label` properties in the connection's YAML block. Identifiers and scenario syntax do not change; there is no description field.
- Delete is blocked for any scenario reference, default-directory dependency, inherited/shared/environment connection, unreadable or invalid scenario, read-only workspace, or unsaved form. Delete only the exclusively profile-owned connection block; leave `secrets.env` unchanged.
- Preserve YAML comments and unrelated settings, never overwrite an existing profile, and confine writes to the active workspace.
- Keep English and Spanish UI and API messages, keyboard access, light/dark themes, and narrow screens.
- The user's unrelated edits under `idm-test-scenarios/src/test/resources` must remain untouched.

## Review Focus

- A scenario whose connection name is a `${...}` expression is not provably unused → block deletion as uncertain; Task 5 test.
- A scenario file that fails to parse or disappears during scanning → block deletion and name the file; Task 5 test.
- A connection inherited from `application.yml` with only a profile label override → show it, allow the label override, but block Delete; Tasks 3 and 5 tests.
- A case-insensitive duplicate profile name or symlinked target → reject creation without changing a file outside the workspace; Task 1 test.
- A profile file changed after the Lab panel opened → deletion must not overwrite unrelated edits or remove a newly referenced connection; Task 5 test.

---

## File map

- `idm-test-runner/src/main/java/com/idm/test/runner/ui/lab/ProfileFiles.java` — create a named profile from the commented template with validation and exclusive write.
- `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/WorkspacesController.java` — `POST /api/workspaces/profiles` for the current session workspace and a refreshed sorted profile list.
- `idm-test-runner/src/main/java/com/idm/test/runner/ui/lab/LabSettings.java` — label fields/effective display names, deletion gate, and delete operation.
- `idm-test-runner/src/main/java/com/idm/test/runner/ui/lab/ProfileYamlEditor.java` — remove exactly one active connection subtree while preserving unrelated text.
- `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/LabController.java` — `DELETE /api/lab?profile=&systemId=` with a refreshed Lab view.
- Connector property models (`LdapProperties`, `DatabaseProperties`, `RestProperties`, `SoapProperties`) — accept optional `label` without changing connector behavior.
- `idm-test-runner/src/main/resources/com/idm/test/runner/init/application-lab.yml` — commented label examples; the new-profile header names the chosen profile.
- `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages.properties` and `messages_es.properties` — API errors and the Lab label field.
- `idm-test-runner/src/main/frontend/src/workbench/NewProfileDialog.tsx`, `TopBar.tsx`, `Workbench.tsx`, `Workbench.css` — profile creation dialog, local profile-list update, and Lab opening.
- `idm-test-runner/src/main/frontend/src/lab/LabPanel.tsx`, `SystemForm.tsx`, `Lab.css`, `types.ts` — scannable navigator, label/identifier, deletion gate and mutation.
- `idm-test-runner/src/main/frontend/src/i18n/en.json` and `es.json` — new UI copy.
- Adjacent JUnit/Vitest test files — HTTP, YAML, component, and integration coverage. Follow the existing fixtures and fake server patterns.

### Task 1: Create a profile file through the workspace API

**Files:** Create `idm-test-runner/src/main/java/com/idm/test/runner/ui/lab/ProfileFiles.java`; modify `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/WorkspacesController.java`, `idm-test-runner/src/main/java/com/idm/test/runner/InitCommand.java`, `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages.properties`, `messages_es.properties`, and `idm-test-runner/src/test/java/com/idm/test/runner/ui/api/WorkspacesControllerTest.java`.

**Interfaces:** Produce `ProfileFiles.create(Path workspaceRoot, String name): Path` and `POST /api/workspaces/profiles` body `{name: string}`, reply `{name: string, path: string, profiles: string[]}`. Reuse `LabController.profile` validation where appropriate; reject case-insensitive collisions, symlink escape, and existing targets. Expose the starter template through `InitCommand.profileTemplate(String name): String` with the header's profile name replaced. Write a complete temp file in `config/` and move it exclusively to the target; no partial target.

- [ ] **Step 1: Write failing HTTP tests.** Assert `uat` creates `config/application-uat.yml`, the file has no active YAML keys and has the `uat` header, response has sorted profiles, Lab inherits shared settings, collision `LAB`, unsafe name, symlink target, and no current workspace all fail without overwrite or outside write. Assert `secrets.env` is unchanged.
- [ ] **Step 2: Run RED.** `./mvnw -B -pl idm-test-runner -am test -Dtest=WorkspacesControllerTest -Dsurefire.failIfNoSpecifiedTests=false` → new tests fail on missing endpoint.
- [ ] **Step 3: Implement** the stated API/service/template interfaces and localized errors. Do not change `POST /api/files/new`.
- [ ] **Step 4: Run GREEN** with the same command → all `WorkspacesControllerTest` cases pass.
- [ ] **Step 5: Commit** these Task 1 files with `feat: create commented workspace profiles`.

### Task 2: Create and select a profile in the top bar

**Files:** Create `idm-test-runner/src/main/frontend/src/workbench/NewProfileDialog.tsx`; modify adjacent `TopBar.tsx`, `Workbench.tsx`, `Workbench.css`, `EditorPane.test.tsx`, `idm-test-runner/src/main/frontend/src/i18n/en.json`, `es.json`, and `idm-test-runner/src/main/frontend/src/test/workbenchServer.ts`.

**Interfaces:** Consume Task 1's `POST /api/workspaces/profiles`. `TopBar` gains `onNewProfile(): void` and `canCreateProfile: boolean`. `Workbench` owns a mutable `profiles` list initialized from `workspace.profiles`; on success replace that list, refresh the tree, set the new profile, and call `labStore.openPanel()` after switching. `NewProfileDialog` accepts `open`, `busy`, `error`, `onCancel`, `onCreate(name)` and shows `config/application-<name>.yml`.

- [ ] **Step 1: Write failing UI tests.** Assert New profile is available with zero profiles, submits a valid name once, updates the selector/tree and opens the new Lab panel; Cancel creates nothing; invalid/duplicate names and server errors stay in the dialog; read-only disables it; unsaved Lab edits prompt before any creation and cancellation leaves files/profile unchanged. Test English/Spanish names.
- [ ] **Step 2: Run RED.** `npm --prefix idm-test-runner/src/main/frontend test -- --run src/workbench/EditorPane.test.tsx` → new tests fail on missing control/dialog.
- [ ] **Step 3: Implement** the dialog and workbench flow. Keep the dropdown a native select and place the button beside it; preserve current profile-switch confirmation.
- [ ] **Step 4: Run GREEN** with the same command and `npm --prefix idm-test-runner/src/main/frontend run build` → all focused tests and build pass.
- [ ] **Step 5: Commit** Task 2 files with `feat: add new profile workflow`.

### Task 3: Persist and expose profile-specific connection labels

**Files:** Modify `idm-test-runner/src/main/java/com/idm/test/runner/ui/lab/LabSettings.java`, `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/config/LdapProperties.java`, `idm-connector-database/src/main/java/com/idm/test/connector/database/config/DatabaseProperties.java`, `idm-connector-rest/src/main/java/com/idm/test/connector/rest/config/RestProperties.java`, `idm-connector-soap/src/main/java/com/idm/test/connector/soap/config/SoapProperties.java`, `idm-test-runner/src/main/resources/com/idm/test/runner/init/application-lab.yml`, the two message bundles, `idm-test-runner/src/test/java/com/idm/test/runner/ui/api/LabControllerTest.java`, and adjacent connector binding tests.

**Interfaces:** Add a non-secret `label` field first in every connection kind's `LabSettings` field list. `SystemView.name` remains the effective display name: nonblank resolved label, else existing localized fallback. Keep `SystemView.id` stable. `PUT /api/lab` saves `values.label` into that connection's profile YAML block; blank label overrides any inherited label and restores fallback. Extend each connector property model with an optional ignored-by-runtime label accessor so Spring binding remains valid.

- [ ] **Step 1: Write failing tests.** Assert label in `application-lab.yml` appears as `SystemView.name` and a `label` field with `profile` provenance; another profile uses its own label; a shared label is inherited, a blank profile override restores fallback, and identifiers/`usedByScenarios` remain unchanged. Exercise legacy LDAP, named directory, database, REST, and SOAP binder/run loading.
- [ ] **Step 2: Run RED.** `./mvnw -B -pl idm-test-runner -am test -Dtest=LabControllerTest -Dsurefire.failIfNoSpecifiedTests=false` → new label tests fail.
- [ ] **Step 3: Implement** the label field, effective name, template examples, and model accessors; preserve existing secret handling.
- [ ] **Step 4: Run GREEN** with the same command, then `./mvnw -B -pl idm-test-runner -am test -Dsurefire.failIfNoSpecifiedTests=false` → targeted and module suites pass.
- [ ] **Step 5: Commit** Task 3 files with `feat: label lab connections per profile`.

### Task 4: Replace wrapping tabs with the scannable navigator

**Files:** Modify `idm-test-runner/src/main/frontend/src/lab/LabPanel.tsx`, `SystemForm.tsx`, `Lab.css`, `types.ts`, `fieldTemplates.ts`, `LabPanel.test.tsx`, and `idm-test-runner/src/main/frontend/src/i18n/en.json`, `es.json`.

**Interfaces:** Consume Task 3's `LabSystem.name` and `fields['label']`. Keep the existing `LabStore.selected` and mounted forms. Use a vertically oriented tablist with bounded scrolling, status icons, selected state, `ArrowUp`/`ArrowDown`/`Home`/`End` navigation, a search box filtering label/kind/id, and Add in the list heading. Search does not silently change the selected form. Show read-only Identifier before Label/connection fields; Add still chooses immutable kind/id and may set Label.

- [ ] **Step 1: Write failing tests.** Render 25 systems and assert the list has a bounded scroll area while the form remains visible; search by label/id/kind filters choices but retains current form until selection; keyboard selection/focus works; Save changes only label and updates list text; empty label restores fallback; Add sends label with other values. Check narrow layout semantics and Spanish labels.
- [ ] **Step 2: Run RED.** `npm --prefix idm-test-runner/src/main/frontend test -- --run src/lab/LabPanel.test.tsx` → new tests fail on missing navigator/label UI.
- [ ] **Step 3: Implement** the approved A layout, widen desktop panel within viewport, stack bounded navigator above form on mobile, and retain existing Save/Test/Discard, status, focus, and unsaved protections.
- [ ] **Step 4: Run GREEN** with the same command, then `npm --prefix idm-test-runner/src/main/frontend test -- --maxWorkers=4` and `npm --prefix idm-test-runner/src/main/frontend run build` → all pass.
- [ ] **Step 5: Commit** Task 4 files with `feat: make lab connections scannable`.

### Task 5: Delete only unused profile-owned connections

**Files:** Create `idm-test-runner/src/main/java/com/idm/test/runner/ui/lab/ScenarioReferenceScan.java`; modify `idm-test-runner/src/main/java/com/idm/test/runner/ui/lab/LabSettings.java`, `ProfileYamlEditor.java`, `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/LabController.java`, both API message bundles, `idm-test-runner/src/test/java/com/idm/test/runner/ui/api/LabControllerTest.java`, and `idm-test-runner/src/test/java/com/idm/test/runner/ui/lab/ProfileYamlEditorTest.java`.

**Interfaces:** Add `SystemView.deletion` as `{allowed: boolean, reason: string|null, files: List<String>}` where reasons are `referenced`, `shared`, `environment`, `default-directory`, `invalid-scenario`, and `not-configured`. Add `LabSettings.delete(Path root, String profile, String systemId): void`. `DELETE /api/lab?profile=&systemId=` returns refreshed `LabSettings.View`. The server recomputes the gate at mutation time. `ProfileYamlEditor.removeSubtree(Path file, String dottedPrefix)` removes only the active connection subtree under the profile's own config, under file lock, preserving unrelated lines/comments. Leave secrets untouched.

- [ ] **Step 1: Write failing tests.** Assert deletion succeeds for an unused profile-only directory/database/REST/SOAP/legacy LDAP block and preserves neighbors, comments, encoding/line endings, and `secrets.env`. Assert referenced scenarios, effective default directory, shared or environment source, label-only override of shared connection, invalid/unreadable scenario, dynamic `${...}` target, symlink/outside path, and concurrent profile-file edit block or preserve unaffected content. On rejection assert no file changes and a localized reason/file path.
- [ ] **Step 2: Run RED.** `./mvnw -B -pl idm-test-runner -am test -Dtest=LabControllerTest,ProfileYamlEditorTest -Dsurefire.failIfNoSpecifiedTests=false` → new deletion tests fail.
- [ ] **Step 3: Implement** strict reference scanning for deletion, `SystemView.deletion`, deletion API, and the text-preserving subtree editor. Fail closed on uncertain scans; never depend solely on the UI's prior gate.
- [ ] **Step 4: Run GREEN** with the same command, then `./mvnw -B -pl idm-test-runner -am test -Dsurefire.failIfNoSpecifiedTests=false` → targeted and module suites pass.
- [ ] **Step 5: Commit** Task 5 files with `feat: safely delete unused lab connections`.

### Task 6: Expose deletion and complete rendered QA

**Files:** Modify `idm-test-runner/src/main/frontend/src/lab/types.ts`, `LabPanel.tsx`, `SystemForm.tsx`, `Lab.css`, `LabPanel.test.tsx`, `idm-test-runner/src/main/frontend/src/i18n/en.json`, `es.json`, and `idm-test-runner/src/main/frontend/src/test/labServer.ts`.

**Interfaces:** Consume Task 5's `LabSystem.deletion` and `DELETE /api/lab`. Add `SystemForm.onDeleted(view: LabView, systemId: string): void`, handled by `LabPanel` to accept the returned view, call `useWorkbench().refreshTree()`, and select/focus the next connection or Add. Existing connections show Delete with the server's reason and files when blocked. On allowed delete, confirm connection/profile path and call the API. Disable delete for read-only, busy, or dirty form even if the server says allowed. Do not remove secrets.

- [ ] **Step 1: Write failing tests.** Assert referenced/shared/environment/default/invalid-scenario reasons disable Delete and show dependency names; an unused profile-owned connection prompts, cancellation makes no request, confirmation sends the ID/profile and updates list/selection; stale-server rejection leaves the form and shows the error; dirty/read-only states cannot delete. Check keyboard and Spanish copy.
- [ ] **Step 2: Run RED.** `npm --prefix idm-test-runner/src/main/frontend test -- --run src/lab/LabPanel.test.tsx` → new deletion tests fail.
- [ ] **Step 3: Implement** the control and confirmation within the accepted panel style, and use the Lab store's existing accept/reload behavior.
- [ ] **Step 4: Run GREEN** with the same command, full frontend tests/build, and `./mvnw -B -pl idm-test-runner -am test -Dsurefire.failIfNoSpecifiedTests=false` → all pass.
- [ ] **Step 5: Rendered QA.** Run a disposable local workspace and compare the accepted scannable-list mockup to the app in IAB: desktop and mobile, many connections, both themes and languages, New profile, label override, Delete blocked and allowed, keyboard focus, no clipping or console errors. Record mismatches and fix them before completion.
- [ ] **Step 6: Commit** Task 6 files with `feat: manage lab connections in the panel`.
