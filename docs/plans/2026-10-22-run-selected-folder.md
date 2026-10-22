# Run the Selected Folder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clicking a folder in the workbench's file tree selects it, the Run menu's "Run folder" runs that folder, and during a run the tree marks every scenario of the run as running or waiting.

**Architecture:** Front end only. The workbench keeps a "run folder" choice that follows the last click in the tree (a folder sets it; a scenario file clears it so the menu falls back to that scenario's folder, as today). The file tree highlights the selected folder and takes a per-path run marker map instead of the single running path.

**Tech Stack:** React + TypeScript + Vitest (`idm-test-runner/src/main/frontend`).

**Spec:** ITF-64 (https://optima-cyber-dev.youtrack.cloud/issue/ITF-64). Small change; the design was agreed in chat on 2026-10-22 and is recorded in *Design* below. Background: `docs/guides/workbench.md`.

## Design (agreed 2026-10-22)

- Clicking a folder still opens/closes it and now also **selects** it (highlighted row; Enter/Space do the same). "Run folder `<dir>/` (n)" in the Run menu runs the selected folder, subfolders included.
- The main ▶ Run button keeps running the scenario open in the editor; selecting a folder never changes that.
- **Calls made by Claude, accepted:**
  - the run folder follows the last click — clicking a scenario file after a folder switches "Run folder" back to that scenario's folder, as today;
  - the top-level `scenarios/` folder gets no "Run folder" item ("Run all" covers it); an empty folder shows the item disabled with (0);
  - the selection is not remembered after a page reload;
  - during a run the tree marks every scenario of the run: running (spinner), waiting (○), then its result; today only the running scenario is marked.

## Global Constraints

- Selected folder row: `aria-selected="true"` (or the tree's existing selection attribute pattern) and the theme's selection style; one selected folder at a time.
- Tree marker for a waiting scenario: `○` with accessible label `ui.run.state.waiting` (existing key if present; else add en `waiting` / es `en espera`).
- Menu copy unchanged: `ui.run.menu.folder` with `{folder}` = the path below `scenarios/` plus `/`, and `{count}`.
- No server change; engine and CLI untouched.
- Commit trailer: a blank line, `ITF-64`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Build: `npm test` green and `./mvnw -B verify` green, YAML corpus 0 FAILED.

## Review Focus

- A folder selected, then the user opens a scenario in another folder: "Run folder" names the scenario's folder, not the stale one — pinned in Task 1 (`scenario click moves the run folder`).
- Selecting a folder while a scenario is open: ▶ Run (and ⌘↵) still run the open scenario — pinned in Task 1 (`Run keeps running the open scenario`).
- Collapsing a selected folder keeps it selected, and selecting a folder never opens a file or changes the active tab — pinned in Task 1 (`selecting does not open a file`).
- A run of 3 scenarios: the tree shows ◌ on the running one and ○ on the two waiting; after `runFinished` no run markers remain — pinned in Task 1 (`tree marks the whole run`).
- Selecting `scenarios/` itself or a folder outside `scenarios/` (e.g. `config/`): no "Run folder" item for it, the menu falls back to the active scenario's folder — pinned in Task 1 (`no run folder for scenarios/ or config/`).

---

### Task 1: Folder selection, Run folder, and the tree's run markers

**Files:**
- Modify: `idm-test-runner/src/main/frontend/src/workbench/FileTree.tsx` (folder selection; `runStates` prop replacing `running`)
- Modify: `idm-test-runner/src/main/frontend/src/workbench/Workbench.tsx` (holds the run-folder choice; passes run markers)
- Modify: `idm-test-runner/src/main/frontend/src/run/RunButton.tsx` (`folderOf` fallback; takes the run folder)
- Modify: `idm-test-runner/src/main/frontend/src/workbench/Workbench.css` (selected folder style), `i18n/en.json`, `i18n/es.json` (only if a key is missing)
- Modify: `docs/guides/workbench.md` (running section: how to run a folder)
- Test: `workbench/FileTree.test.tsx`, `run/RunButton.test.tsx`

**Interfaces:**
- Produces:
  - `FileTree` props: `selectedFolder: string | null`, `onSelectFolder(path: string): void`, `runStates: ReadonlyMap<string, 'running' | 'waiting'>` (workspace-relative paths; replaces `running`).
  - `RunButton` takes `runFolder: string | null` — the folder to offer; `null` falls back to `folderOf(active)`. A run folder equal to `scenarios` or not under `scenarios/` offers no item.

- [ ] **Step 1: Failing tests.**
  - `FileTree.test.tsx`:
    - `clicking a folder selects it`: click `scenarios/users` → `onSelectFolder('scenarios/users')`; the row with `selectedFolder='scenarios/users'` has `aria-selected="true"`; it also toggles open/closed as before.
    - `keyboard selects too`: Enter on the folder row → same as a click.
    - `selecting does not open a file`: `onOpen` not called by a folder click.
    - `tree marks the whole run`: `runStates` `{a: running, b: waiting, c: waiting}` → ◌ (label "running") on a, ○ (label "waiting") on b and c, no last-result badge on those three; other scenarios keep their badges.
  - `RunButton.test.tsx`:
    - `runs the selected folder`: `runFolder='scenarios/roles'` with the active scenario in `scenarios/users` → menu shows "Run folder roles/ (n)" and choosing it posts `scenarios: {folder: 'scenarios/roles'}`.
    - `scenario click moves the run folder` (Workbench-level or RunButton with the Workbench rule): folder `scenarios/roles` selected, then a scenario in `scenarios/users` opened → menu shows "Run folder users/".
    - `Run keeps running the open scenario`: with `runFolder` set, ▶ Run and ⌘↵ post `scenarios: [<active scenario>]`.
    - `no run folder for scenarios/ or config/`: `runFolder='scenarios'` or `'config'` → no selected-folder item; the active scenario's folder item (if any) is shown instead.
    - `empty folder disabled`: a selected folder with no scenarios → "Run folder x/ (0)" disabled.
- [ ] **Step 2:** run them → fail (vitest via the Maven-installed node: `export PATH="$PWD/idm-test-runner/target/node:$PATH"`, then `npm test` in `idm-test-runner/src/main/frontend`).
- [ ] **Step 3: Implement.** Workbench state `runFolder` set by `onSelectFolder`, cleared when a file is opened from the tree; `runStates` built from `runStore` rows (`running` → running, `waiting` → waiting) only while the run's phase is `starting`/`running`.
- [ ] **Step 4:** tests pass; guide updated (click a folder, then Run ▾ → Run folder; the tree shows the whole run's progress); `./mvnw -B verify` green, corpus 0 FAILED.
- [ ] **Step 5: Commit** — `feat: run the folder selected in the file tree, and mark the whole run in the tree`.
