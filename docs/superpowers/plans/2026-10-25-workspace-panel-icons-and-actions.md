# Workspace Panel Icons and Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh Workspace controls and make Config and Scenarios reachable from its collapsed rail.

**Architecture:** A small local SVG component supplies one outline icon family. `FileTree` owns the expanded tree, folder reveal, and action row; `Workbench` owns the collapsed rail and passes a one-shot folder reveal request when it expands. Existing create, upload, folder selection, and panel preferences stay in place.

**Tech Stack:** React 19, TypeScript, Vite, Vitest, Testing Library, existing CSS.

**Spec:** [Workspace panel icons and actions design](../specs/2026-10-25-workspace-panel-icons-and-actions-design.md)

## Global Constraints

- No backend changes, new icon package, extra panel, or change to workspace data.
- Keep **New scenario** and **Add files** labeled and equally prominent, in an action row below the Workspace heading.
- Preserve folder disclosure, file opening, run-folder selection, upload/create behavior, panel size persistence, light/dark themes, and English/Spanish support.
- Rail shortcuts are shown only for Config and Scenarios folders present in the current tree. They reveal the folder without selecting it for Run.
- At 170 px expanded width, action labels remain visible and controls do not clip; mobile remains a stacked layout.
- SVG icons use roughly 16–19 px geometry, 1.6–1.8 px rounded strokes, `currentColor`, muted normally and accent on hover/focus.

## Review Focus

- Missing `config/` or `scenarios/` in the current tree → no dead rail shortcut; Task 2 tests.
- `config/` folded before collapsing → its rail shortcut expands, unfolds, and focuses Config; Task 2 tests.
- A nested Run folder selected before using a rail shortcut → rail reveal does not change Run selection; Task 2 tests.
- Tree changes while Workspace is collapsed → rail reflects the latest root folders and does not target a removed path; Task 2 tests.
- Keyboard activation of a rail shortcut → revealed folder has focus and the shortcut has an accessible label in English and Spanish; Task 2 tests.

---

## File map

- `src/workbench/WorkspaceIcon.tsx` — local SVG icons for panel controls, folder identities, disclosure, and file actions.
- `src/workbench/FileTree.tsx` — expanded header, action row, folder icons, and requested folder reveal.
- `src/workbench/Workbench.tsx` — collapsed Workspace rail buttons and reveal request.
- `src/workbench/Workbench.css` — action row wrapping, icon sizing, outline control states, vertical/horizontal rail layout.
- `src/i18n/en.json` and `es.json` — rail shortcut names/tooltips.
- `src/workbench/FileTree.test.tsx` and `EditorPane.test.tsx` — behavior and integration coverage.

### Task 1: Outline icons and balanced Workspace action row

**Files:** Create `src/workbench/WorkspaceIcon.tsx`; modify `src/workbench/FileTree.tsx`, `Workbench.css`, and `FileTree.test.tsx`.

**Interfaces:** Export `WorkspaceIcon({ name }: { name: 'collapse' | 'expand' | 'config' | 'scenarios' | 'new' | 'upload' | 'chevron' })`. Icons are decorative (`aria-hidden`) inside already named buttons. Config and Scenarios receive distinct icons; every folder keeps a separate SVG disclosure chevron, including other folders.

- [ ] **Step 1: Write failing tests.** In `FileTree.test.tsx`, add `shows outline panel and distinct top-level folder icons`: render the current tree and assert the named Collapse Workspace button contains the collapse SVG, Config and Scenarios each contain their corresponding SVG, and their independent `aria-expanded` behavior still works. Add `keeps labeled file actions in a separate row`: assert both named buttons remain visible and enabled, their SVG icons exist, and the action row is separate from the heading; existing create and upload tests must remain intact.
- [ ] **Step 2: Run** `npm test -- --run src/workbench/FileTree.test.tsx` from `idm-test-runner/src/main/frontend`; expect the new tests to fail.
- [ ] **Step 3: Implement** `WorkspaceIcon`, replace the Workspace header triangle and all folder text carets with the agreed SVGs, and add Config/Scenarios identities. Move both existing actions into a compact outlined row under the heading. Use the existing handlers and labels. Let the action row wrap at narrow pane widths; do not change file-row behavior or Run selection.
- [ ] **Step 4: Run** the same tests and `npm run build`; expect PASS and no TypeScript errors. Check at 170 px with a rendered browser viewport before committing.
- [ ] **Step 5: Commit** Task 1 files with `feat: refine workspace tree icons and actions`.

### Task 2: Config and Scenarios shortcuts in collapsed Workspace rail

**Files:** Modify `src/workbench/Workbench.tsx`, `FileTree.tsx`, `Workbench.css`, `src/i18n/en.json`, `es.json`, `EditorPane.test.tsx`, and `FileTree.test.tsx`; consume Task 1's `WorkspaceIcon`.

**Interfaces:** Add `revealFolder?: 'config' | 'scenarios' | null` to `FileTree`. `Workbench` passes it only when a rail folder shortcut expands Workspace. A plain Expand Workspace request passes `null`. `FileTree` unfolds the requested path and focuses/scrolls its row without calling `onSelectFolder`.

- [ ] **Step 1: Write failing tests.** In `EditorPane.test.tsx`, test `rail shows only current Config and Scenarios folders` by rendering trees with both, one, and neither folder; test `rail Config shortcut reveals folded folder without changing Run folder` using a selected nested Run folder before collapse; test `rail follows tree refresh while collapsed`; test `rail shortcuts are keyboard named and focus the revealed folder`, including Spanish after `setLanguage('es', false)`. In `FileTree.test.tsx`, assert `revealFolder="config"` opens Config and focuses its row without firing `onSelectFolder`.
- [ ] **Step 2: Run** `npm test -- --run src/workbench/EditorPane.test.tsx src/workbench/FileTree.test.tsx`; expect new tests to fail.
- [ ] **Step 3: Implement** three icon buttons in the 36 px rail: Expand Workspace, then conditional Config and Scenarios shortcuts. Use current `tree.children`, add English/Spanish accessible names and titles, and pass the reveal request on expansion. In `FileTree`, initialize or set the requested folder open and move focus to its row after render. Update mobile rail CSS so these controls form a horizontal bar with full tap targets.
- [ ] **Step 4: Run** the same tests, full `npm test -- --maxWorkers=4`, and `npm run build`; expect PASS. Render expanded/collapsed Workspace at desktop, the 170 px minimum, mobile, light/dark, and English/Spanish; click and keyboard-activate both shortcuts and verify no clipping or console errors.
- [ ] **Step 5: Commit** Task 2 files with `feat: reveal workspace folders from collapsed rail`.
