# Adjustable Workbench Panels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users resize and collapse Workspace, Run, and Problems, and open any workspace file from an editor chooser.

**Architecture:** Keep the existing React workbench and its three-column grid. A small layout module owns workspace-scoped browser preferences, a reusable splitter handles pointer and keyboard resizing, and Workbench/EditorPane render the rails and Problems bar. The file chooser consumes the existing tree state and opens files through `WorkbenchStore.open`.

**Tech Stack:** React 19, TypeScript 6, Vite 8, Vitest 5, Testing Library, existing CSS and browser preference helper. No backend changes.

**Spec:** [Adjustable workbench panels and workspace file picker](../specs/2026-10-25-resizable-workbench-panels-design.md)

## Global Constraints

- Keep editor tabs, save/draft behavior, run actions, and the current three-column order.
- `secrets.env` remains visible but locked; the chooser cannot open it.
- Use the current workspace tree as the chooser's source and `WorkbenchStore.open` for selection.
- Persist only layout widths/heights and collapsed flags, per workspace path; never file content or secrets.
- English and Spanish UI, light and dark themes, keyboard and pointer access, and narrow-screen stacking remain supported.

## Review Focus

- Corrupt or unavailable browser preference storage → defaults still render; Task 1 test.
- Viewport narrower than saved panel sizes → center editor remains usable without page overflow; Task 2 test.
- Similar file names in different folders → chooser displays paths and selects the correct path; Task 4 test.
- `secrets.env` in the tree → chooser displays its lock and cannot open it; Task 4 test.
- File tree refresh while chooser is open → results follow the current tree and removed paths cannot be chosen; Task 4 test.

---

## File map

- `src/workbench/panelLayout.ts` — layout defaults, workspace preference read/write, viewport clamping, state hook.
- `src/workbench/PanelSplitter.tsx` — accessible draggable and keyboard-operable divider.
- `src/workbench/Workbench.tsx` and `Workbench.css` — horizontal tracks, left/right rails, Run progress, reset control, responsive layout.
- `src/workbench/EditorPane.tsx`, `ProblemsPanel.tsx`, and `Workbench.css` — vertical Problems splitter and collapsed bar.
- `src/workbench/WorkspaceFilePicker.tsx` — searchable tree-backed chooser.
- `src/i18n/en.json` and `es.json` — controls, instructions, and accessible labels.
- Matching `*.test.ts(x)` files — behavior at the boundaries above.

### Task 1: Layout state and splitter

**Files:** Create `src/workbench/panelLayout.ts`, `panelLayout.test.ts`, `PanelSplitter.tsx`, `PanelSplitter.test.tsx`; use `src/storage.ts`.

**Interfaces:** Produce `PanelLayout` with `workspaceWidth`, `runWidth`, `problemsHeight`, `workspaceCollapsed`, `runCollapsed`, `problemsCollapsed`; `DEFAULT_LAYOUT` is 230 px, 320 px, 180 px, and all expanded. `readPanelLayout(workspacePath: string): PanelLayout` and `writePanelLayout(workspacePath: string, layout: PanelLayout): void` use `idmtest.layout.v1:${workspacePath}`. `usePanelLayout(workspacePath: string)` returns `{ layout, update(patch: Partial<PanelLayout>), reset() }`. `clampPanelLayout(layout: PanelLayout, viewportWidth: number, viewportHeight: number): PanelLayout` keeps the center editor at least 320 px wide; it clamps rendered values without erasing saved expanded sizes. `PanelSplitter` props are `{ orientation: 'vertical' | 'horizontal', label: string, value: number, min: number, max: number, onChange(value: number): void }`. Orientation names describe the divider line: vertical changes width with Left/Right keys; horizontal changes height with Up/Down keys.

- [ ] **Step 1: Write failing tests.** In `panelLayout.test.ts`, name `restores only this workspace`, `ignores bad storage`, and `clamps saved widths at narrow viewports`; assert `expect(readPanelLayout('/a')).toMatchObject({ workspaceWidth: 230, runWidth: 320, problemsHeight: 180 })`, a stored `workspaceCollapsed: true` restores for `/a` but not `/b`, and `clampPanelLayout(saved, 800, 600)` leaves at least 320 px for the editor. In `PanelSplitter.test.tsx`, name `resizes by pointer and keyboard`; with value 200 and bounds 100–300 assert `expect(onChange).toHaveBeenCalledWith(216)` after Arrow, `100` after Home, `300` after End, and a bounded value after drag; assert `getByRole('separator')` has orientation and value attributes.
- [ ] **Step 2: Run** `npm test -- --run src/workbench/panelLayout.test.ts src/workbench/PanelSplitter.test.tsx` from `idm-test-runner/src/main/frontend`; expect the new tests to fail.
- [ ] **Step 3: Implement** the interfaces above using the guarded `readPreference`/`writePreference`. Keep pointer capture and cleanup inside `PanelSplitter`; clamp all values before calling `onChange`.
- [ ] **Step 4: Run** the same tests; expect PASS.
- [ ] **Step 5: Commit** only Task 1 files with `feat: add workbench panel layout state and splitters`.

### Task 2: Workspace and Run rails

**Files:** Modify `src/workbench/Workbench.tsx`, `Workbench.css`, `src/workbench/EditorPane.test.tsx`, `src/i18n/en.json`, `es.json`; consume Task 1 interfaces.

**Interfaces:** Pass Task 3 layout props to `EditorPane`. Keep `FileTree` mounted only when Workspace is expanded; its tree data stays in Workbench. Compute compact Run progress from `RunState`: starting/running state plus completed-or-current count over total, and an accessible label. Add `reset()` to a visible layout control.

- [ ] **Step 1: Write failing tests.** Name `collapses and restores side panels`, `shows live run progress in rail`, and `resets saved layout`; assert `expect(screen.getByRole('button', { name: 'Expand Workspace' })).toBeVisible()` after collapse, `expect(screen.getByLabelText('Run progress')).toHaveTextContent('3/5')` during a five-scenario run, and `expect(readPanelLayout(WORKSPACE.path)).toEqual(DEFAULT_LAYOUT)` after reset. Add a narrow-viewport test that applies a stored oversize layout and asserts clamped track values; do not infer CSS pixel sizes from jsdom.
- [ ] **Step 2: Run** `npm test -- --run src/workbench/EditorPane.test.tsx`; expect new assertions to fail.
- [ ] **Step 3: Implement** grid tracks, splitters, rails, progress, reset, responsive stacking, and translations. Use a resize observer or equivalent to clamp at viewport changes; preserve the top bar's Run and Stop access.
- [ ] **Step 4: Run** the targeted test and `npm run build`; expect PASS.
- [ ] **Step 5: Commit** Task 2 files with `feat: resize and collapse workspace and run panels`.

### Task 3: Problems height and collapsed bar

**Files:** Modify `src/workbench/EditorPane.tsx`, `ProblemsPanel.tsx`, `Workbench.tsx`, `Workbench.css`, `src/workbench/EditorPane.test.tsx`, `src/i18n/en.json`, `es.json`; consume Task 1 layout state and splitter.

**Interfaces:** `EditorPane` gains `problemsHeight: number`, `problemsCollapsed: boolean`, `onProblemsHeight(value: number): void`, and `onProblemsCollapsed(value: boolean): void`. `ProblemsPanel` gains `collapsed: boolean` and `onCollapsed(value: boolean): void`; its region and validation count remain present while collapsed.

- [ ] **Step 1: Write failing tests.** Name `collapses Problems without stopping validation` and `restores Problems height`; assert `expect(screen.getByRole('region', { name: 'Problems' })).toHaveTextContent('2')` while collapsed, its detail rows are hidden, and Arrow on `getByRole('separator', { name: 'Resize Problems' })` changes `aria-valuenow` from `180` to `196`. Switch tabs while collapsed and assert the count changes. Use a narrow-screen match-media stub to assert the divider is absent.
- [ ] **Step 2: Run** `npm test -- --run src/workbench/EditorPane.test.tsx`; expect new assertions to fail.
- [ ] **Step 3: Implement** the interfaces, vertical layout, bar, and translated controls. Keep problem details out of the collapsed view while leaving validation active.
- [ ] **Step 4: Run** the targeted test and `npm run build`; expect PASS.
- [ ] **Step 5: Commit** Task 3 files with `feat: resize and collapse problems panel`.

### Task 4: Editor workspace file chooser

**Files:** Create `src/workbench/WorkspaceFilePicker.tsx`, `WorkspaceFilePicker.test.tsx`; modify `EditorPane.tsx`, `Workbench.tsx`, `Workbench.css`, `src/workbench/EditorPane.test.tsx`, `src/i18n/en.json`, `es.json`.

**Interfaces:** `WorkspaceFilePicker` takes `{ tree: TreeNode | null, active: string | null, dirty: ReadonlySet<string>, onOpen(path: string): void }`. `EditorPane` gains `tree`, `dirty`, and `onOpenFile(path: string): void`. The picker flattens current tree nodes for search but displays folder paths; `kind === 'secrets'` is disabled.

- [ ] **Step 1: Write failing tests.** Name `chooses any workspace file`, `distinguishes same names`, `keeps secrets locked`, and `tracks tree refresh`. Assert `expect(screen.getByRole('option', { name: 'scenarios/users/b.yaml' })).toBeVisible()` even when its tree folder is closed; selection opens that path; choosing an already open path leaves the tab count unchanged; duplicate basenames show distinct paths; `secrets.env` is disabled; rerendering with a refreshed tree removes a deleted result. Assert Escape restores focus to `Open workspace file`, typing a query filters results, Enter selects, and dirty open files show an unsaved mark.
- [ ] **Step 2: Run** `npm test -- --run src/workbench/WorkspaceFilePicker.test.tsx src/workbench/EditorPane.test.tsx`; expect new tests to fail.
- [ ] **Step 3: Implement** the chooser and pass the current tree through Workbench and EditorPane. Keep the picker button outside the tab strip's horizontal scrolling area; follow existing open-file behavior and translations.
- [ ] **Step 4: Run** targeted tests, `npm test`, and `npm run build`; expect PASS. Visually check desktop and narrow widths, both themes, drag/keyboard resizing, editor layout after resize, and chooser placement.
- [ ] **Step 5: Commit** Task 4 files with `feat: open workspace files from editor chooser`.
