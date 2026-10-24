# Lab Panel Default Directory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When scenarios use `ldap.*` steps without `directory:` and the default directory cannot be resolved, the lab panel says so and offers a **Default directory** choice, instead of asking for the old single-directory URL.

**Architecture:** `LabSettings` stops falling back to the legacy `idm.test.ldap` system when `LdapDirectories.resolveDefault` has no answer (or throws). It reports a separate `defaultDirectory` block in the lab view and accepts a save of `idm.test.default-directory` to the profile file. The front end shows that block as the first tab of the lab panel and counts it in the chip and `labState()`.

**Tech Stack:** Java 25 (`idm-test-runner`), React + TypeScript + Vitest (`idm-test-runner/src/main/frontend`).

**Spec:** ITF-72 (https://optima-cyber-dev.youtrack.cloud/issue/ITF-72) — bug; root cause and fix agreed in chat on 2026-10-24 and recorded below.

## Design (agreed 2026-10-24)

- **Root cause:** `LabSettings.directoryId` resolves the default directory like the runner does, but on "no answer" it falls back with `.orElse(DEFAULT)` (and on `IllegalStateException` returns `"ldap"`), so the panel asks for the legacy `idm.test.ldap` URL. `idmtest run` refuses the same case with `runner.guard.noDefaultDirectory`.
- **Fix:** the lab panel uses the runner's rule exactly. When steps without `directory:` exist and the default cannot be resolved — several named directories and no `default-directory`, or a `default-directory` that names no configured directory — the panel shows a **Default directory** item: the scenarios that need it, the configured directories to choose from, and Save, which writes `idm.test.default-directory` to `config/application-<profile>.yml` (uncommenting the init template's `#     default-directory:` line when present). The legacy "Directory" system is not offered in that case.
- When the default resolves (one directory, a valid `default-directory`, or the legacy block configured), nothing changes from today.

## Global Constraints

- The runner's resolution is the authority: `LdapDirectories.resolveDefault(configured, names)` with `names` built exactly as `RealEnvironmentGuards` builds them (named directories, plus `default` when none are named or `idm.test.ldap.url` is set). The lab must never resolve a case the runner would refuse, nor refuse one the runner resolves.
- Lab view JSON gains `defaultDirectory`: `null` when not needed or resolvable; otherwise `{usedByScenarios: [...], choices: [...sorted names], value: string|null, state: "missing"|"invalid"}` (`invalid` = configured but not one of `choices`).
- Save: `PUT /api/lab/default-directory?profile=<p>` body `{name}` → 200 with the refreshed view; 400 `ui.lab.badDefaultDirectory` (en/es) when `name` is not in `choices`; written through `ProfileYamlEditor` (comments kept; profile file only; never `application.yml`). Behind the workspace guard like the other lab endpoints.
- UI copy: tab label `ui.lab.defaultDirectory.title` en `Default directory` / es `Directorio predeterminado`; explanation `ui.lab.defaultDirectory.explain` en `{scenarios} use steps without directory:, and there is no default directory. Choose which directory those steps use.` / es `{scenarios} usan pasos sin directory: y no hay un directorio predeterminado. Elija qué directorio usan esos pasos.`; invalid `ui.lab.defaultDirectory.invalid` en `default-directory is "{value}", which is not one of the configured directories.` / es `default-directory es "{value}", que no es ninguno de los directorios configurados.`; chip `ui.lab.chip.defaultDirectory` en `Lab: choose a default directory` / es `Lab: elija un directorio predeterminado` with the ✕ badge.
- `labState()` reports `missing` (blocking Run, as for a missing field) while `defaultDirectory` is not null; the missing item opens the Default directory tab.
- No secret in any response; read-only (second) tab: Save disabled.
- Commit trailer: a blank line, `ITF-72`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Build: `./mvnw -B verify` green and the YAML corpus 0 FAILED.

## Review Focus

- The reported setup — `example.yaml` with directory-less ldap steps; directories `edirectory` and `ad`; no default; no `idm.test.ldap.url` — gives `defaultDirectory` with choices `[ad, edirectory]` and **no** `ldap` system — pinned in Task 1 (`testAmbiguousDefaultDirectory`).
- `default-directory: nope` with directories `edirectory`, `ad` → `state: "invalid"`, `value: "nope"`, no `ldap` system — pinned in Task 1 (`testInvalidDefaultDirectory`).
- One named directory only, or legacy `idm.test.ldap.url` set, or a valid default → `defaultDirectory` null and the systems exactly as before — pinned in Task 1 (`testResolvableDefaultUnchanged`).
- Saving `edirectory` writes `default-directory: edirectory` under `idm.test` in the profile file, uncommenting the template line, every other byte unchanged; then the view has no `defaultDirectory` and example.yaml is listed under `directory:edirectory` — pinned in Task 1 (`testSaveDefaultDirectory`).
- The panel and chip in that state: ✕ "Lab: choose a default directory", Run blocked with that message, choosing and saving clears it — pinned in Task 2 (`default directory choice`).

---

### Task 1: Server — report and save the default directory

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/lab/LabSettings.java` (`directoryId`, `View`, new save), `ui/api/LabController.java` (new PUT), runner catalogs (`runner.ui.lab.badDefaultDirectory`)
- Test: `ui/api/LabControllerTest.java` (and `ui/lab/ProfileYamlEditorTest.java` only if the editor needs a change)

**Interfaces:**
- Produces: `LabSettings.View(List<SystemView> systems, DefaultDirectory defaultDirectory)`; `record DefaultDirectory(List<String> usedByScenarios, List<String> choices, String value, String state)`; `void saveDefaultDirectory(Path root, String profile, String name)`; `PUT /api/lab/default-directory?profile=` `{name}`.

- [ ] **Step 1: Failing tests** — the four Task 1 lines of Review Focus, over HTTP against a temp workspace (`LabFixture` / existing LabControllerTest helpers), plus: `PUT` with a name not in choices → 400 `ui.lab.badDefaultDirectory` (en and es), file unchanged; a second tab's PUT behaves like the other lab saves under the workspace guard.
- [ ] **Step 2:** run them → fail (`./mvnw -B test -pl idm-test-runner -am -Dtest='LabControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`).
- [ ] **Step 3: Implement.** Build `names` as the guard does; on empty or `IllegalStateException`, attribute the directory-less needs to the `defaultDirectory` block instead of `"ldap"`. Share the names-building with `RealEnvironmentGuards` if it can be done without changing the guard's behaviour; otherwise mirror it and pin both with the same cases.
- [ ] **Step 4:** focused tests pass; `./mvnw -B verify` green, corpus 0 FAILED.
- [ ] **Step 5: Commit** — `fix: the lab panel asks for a default directory instead of the old single-directory URL`.

### Task 2: Lab panel — the Default directory tab, chip and Run gate

**Files:**
- Modify: `idm-test-runner/src/main/frontend/src/lab/types.ts`, `lab/labState.ts`, `lab/LabPanel.tsx` (new first tab), `lab/LabChip.tsx`, `lab/labStore.ts` (save call), `i18n/en.json`, `i18n/es.json`
- Create: `lab/DefaultDirectoryForm.tsx`
- Modify: `docs/guides/workbench.md` (lab panel section: the default directory)
- Test: `lab/LabPanel.test.tsx`, `lab/LabChip.test.tsx`, `run/RunButton.test.tsx`

**Interfaces:**
- Consumes: Task 1's `defaultDirectory` view block and `PUT /api/lab/default-directory`.

- [ ] **Step 1: Failing tests** (fake server): `default directory choice` — view with `defaultDirectory {usedByScenarios: ['example.yaml'], choices: ['ad','edirectory'], value: null, state: 'missing'}` → chip ✕ "Lab: choose a default directory"; panel's first tab "Default directory" with ✕, the explanation naming example.yaml, a select listing ad and edirectory; choosing edirectory and Save sends `PUT /api/lab/default-directory?profile=lab {name:'edirectory'}`, the refreshed view clears the tab and the chip; Run is blocked with the chip message before the save; `invalid` state shows the invalid text with the value; Spanish copy; read-only tab → Save disabled.
- [ ] **Step 2:** run them → fail (vitest via the Maven-installed node).
- [ ] **Step 3: Implement** with the copy from Global Constraints.
- [ ] **Step 4:** tests and i18n parity pass; guide updated; `./mvnw -B verify` green, corpus 0 FAILED.
- [ ] **Step 5: Commit** — `fix: the lab panel offers a default directory choice when steps need one`.
