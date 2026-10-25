# Adjustable workbench panels and workspace file picker — design

**Decided with:** the user, 2026-10-25. **Builds on:** [the local workbench design](../../specs/2026-10-19-local-workbench-design.md), §4.3. Visual sketches are in `.superpowers/brainstorm/13049-1792957911/content/` (local and ignored by Git).

## Intent

Give the editor enough room for long scenario names and YAML while keeping navigation, validation, and run status close at hand. Users should be able to widen a cramped panel, collapse panels they are not using, and open any workspace file from the editor when the Workspace tree is narrow or collapsed.

The current desktop layout has fixed grid tracks: Workspace is 170–230 px, the editor takes the remainder, and Run is 220–320 px. Problems is capped at 32% of the editor height. The editor has tabs for already open files; the Workspace tree lists workspace files. The new editor button is a second route to the tree's files, not a list restricted to open tabs.

## Goals, non-goals, and anti-goals

**Goals**

- Resize Workspace and Run horizontally and Problems vertically on desktop.
- Collapse Workspace and Run to narrow rails and Problems to a short bar, with clear controls to reopen them.
- Keep run progress visible in the collapsed Run rail; keep the Problems count visible in its collapsed bar.
- Add an editor button that opens or activates any openable file from the current Workspace tree, whether or not it already has a tab.
- Support pointer and keyboard use and avoid an editor that becomes too narrow to work in.

**Non-goals**

- Moving panels to other positions, floating panels, or changing the underlying workspace files.
- Replacing editor tabs or changing current file-opening and saving rules.
- Opening `secrets.env` in the editor; it stays visibly locked as it is in the tree.

**Anti-goals**

- Hiding an active run's progress so completely that the user must reopen Run to know whether it is still going.
- Letting a drag shrink the editor or a panel into an unusable sliver, or losing access to a collapsed panel's reopen control.
- Making the file picker show a different set of openable files from the Workspace tree.

## Approaches

1. **Focused:** Resize Workspace, while Run and Problems can collapse but keep fixed expanded sizes. This addresses the user's most cramped panel with fewer controls, but long run details and problem messages remain constrained.
2. **Consistent — chosen:** Resize Workspace and Run horizontally and Problems vertically; all three collapse. This gives every work area room when needed and keeps the existing three-column arrangement familiar.

Free panel rearrangement was considered and set aside because the user asked to adjust size and visibility within the current arrangement.

## Agreed behavior

### Desktop layout

- Start with today's visual proportions. The editor remains the flexible center pane.
- A draggable divider between Workspace and editor changes Workspace width. A second divider between editor and Run changes Run width. A horizontal divider between editor and Problems changes Problems height.
- Workspace collapses to a narrow left rail with an obvious expand control. Run collapses to a narrow right rail with an expand control and compact live progress (for example, status and `3/5`). Problems collapses to a short bar below the editor with its title, count, and expand control.
- The editor gets the space released by collapsed panels. Expanding a panel restores its last expanded size as far as the current viewport allows.
- Panel headers include explicit collapse controls; dragging and collapsing are separate actions. A reset-layout action restores default sizes and expanded panels. *(Builder call for discoverability and placement.)*

### Editor file picker

- Keep the current horizontally scrollable tab strip. Add a button anchored at its end, outside the scrolling tab area, labeled **Open workspace file** (translated to English and Spanish).
- The button opens a searchable chooser based on the same current tree data as Workspace. It includes nested folders and all openable files, with folder paths visible to distinguish repeated names. It still works while Workspace is collapsed.
- Choosing a file uses the existing open-file path: activate its tab if open, otherwise open a new tab. The chooser closes and focus goes to the selected file's editor or tab.
- Show `secrets.env` with a lock but do not allow selecting it, matching the tree's current behavior. Show an unsaved mark for files already open with edits. New, deleted, and renamed-on-disk files follow the same refreshed tree state that powers Workspace.
- Search matches file name and relative path; keyboard users can open the chooser, type, move through results, select, and dismiss with Escape. *(My call: search is useful when the workspace contains many scenarios.)*

### Accessibility and responsive behavior

- Dividers are visible on hover and keyboard focus, with a comfortable pointer target. They expose the WAI-ARIA window splitter semantics, keyboard size adjustment, and current size. Collapse and expand buttons have clear names and focus behavior.
- Enforce minimum usable widths/heights for all expanded panes and the editor; clamp stored sizes when the viewport changes. Avoid page-level horizontal overflow.
- In the existing narrow-screen stacked layout, panels remain collapsible with labeled headers. Drag dividers do not appear where panels are stacked; the editor file picker remains available.
- Respect the existing light/dark themes and English/Spanish catalogs. Resizing should not interfere with Monaco's own scrolling, selection, or editor layout.

## Constraints and builder choices

- This is frontend work in the current React/Vite workbench. Reuse the existing `Workbench` tree state, `WorkbenchStore.open`, editor tabs, `RunStore`, and `ProblemsPanel`; no backend endpoint is needed.
- The current workspace's panel sizes and collapsed states should survive a page reload in browser preference storage, keyed by workspace path. Store only layout numbers/booleans, never file content or secrets. *(My call.)*
- The builder may choose a small accessible splitter dependency or local splitter components after checking fit with the current CSS grid. Either must meet the behavior and accessibility requirements above.
- Exact minimum dimensions, keyboard step size, rail widths, responsive breakpoint, and visual styling are builder decisions to settle through rendering and interaction checks.
