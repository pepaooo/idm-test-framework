# Workspace panel icons and actions — design

**Decided with:** the user, 2026-10-25. **Builds on:** [Adjustable workbench panels](2026-10-25-resizable-workbench-panels-design.md). The local browser mockups are in `.superpowers/brainstorm/34557-1792981019/content/` (ignored by Git).

## Intent

Make Workspace easier to understand at a glance, particularly after it collapses to a narrow rail. Replace the tiny text triangle used to collapse the panel, distinguish Config and Scenarios with meaningful folder icons, and keep the two file actions clear when Workspace is resized.

## Goals and boundaries

- Give Workspace a recognizable panel collapse/expand control, and Config and Scenarios distinct icons in both the expanded tree and collapsed rail.
- A click on either rail folder icon expands Workspace, unfolds that folder, and reveals its row. The user can then continue navigating normally.
- Keep **New scenario** and **Add files** labeled and equally prominent in a compact action row.
- Preserve folder disclosure, file opening, run-folder selection, upload/create behavior, panel size persistence, light/dark themes, and English/Spanish support.
- No backend changes, new icon package, extra panel, or change to workspace data. A rail shortcut must not select a folder for running merely because the user expanded Workspace.
- Avoid cramped or clipped controls at the existing 170 px minimum expanded width and in the stacked mobile layout.

## Approaches considered and chosen

### Icon treatment

1. **Quiet outline — chosen.** One small, consistent SVG stroke family. Config uses a folder with a gear; Scenarios uses a folder with a checklist. A panel-shaped collapse/expand icon replaces the tiny triangle. This fits the workbench's restrained colors and leaves filenames prominent.
2. **Tinted folders.** The same metaphors sit in blue tinted squares in rows and rail. More visible, but visually dense next to status badges and action controls.

### File actions

1. **Balanced outlines — chosen.** Two compact outlined buttons, each with a small SVG icon and full label, in their own row below the Workspace heading. Both actions remain discoverable.
2. **Create first.** A filled accent New scenario button and quieter Add files action. Stronger priority than the user requested.
3. **Compact upload.** New scenario keeps its label while Add files becomes icon-only. Saves width but hides the less familiar action behind a tooltip.

## Agreed surface and behavior

- The expanded header keeps the Workspace title on one line and shows an outline panel-collapse button at the trailing edge. Its accessible name and tooltip describe **Collapse Workspace**. The rail has the mirrored outline panel-expand button, labeled **Expand Workspace**.
- Folder rows keep a separate disclosure chevron that rotates between closed and open states. Place the Config or Scenarios icon between that chevron and the name. Icons indicate folder purpose; chevrons alone indicate expansion.
- The collapsed rail contains the panel-expand button followed by Config and Scenarios shortcut buttons, only for folders present in the current tree. Each has an accessible name and tooltip. The rail uses the same outline icons as the expanded rows and a restrained accent hover/focus state.
- Clicking a rail shortcut expands Workspace, unfolds the target even if it was previously folded, scrolls its row into view, and moves keyboard focus to that row. It does not open a file or change the Run folder.
- The action row sits below the header. Buttons are short rounded rectangles with thin borders: plus + **New scenario**, upload + **Add files**. Use the existing wording and enabled/disabled behavior. Keep labels visible; at narrow panel widths the buttons wrap or stack instead of shrinking text or clipping.
- Use a consistent SVG family: approximately 16–19 px, 1.6–1.8 px strokes, rounded joins, `currentColor`, muted by default, accent on hover or keyboard focus. Retain the existing palette, typography, tree row density, and file/status marks.
- At the mobile stacked breakpoint, keep the expand button and both folder shortcuts accessible in a horizontal bar with readable tap targets. The panel still expands normally; no drag divider appears there.

## Implementation constraints and verification

- Reuse the current React/Vite workbench, `FileTree`, tree data, and `Workbench` panel state. Small local SVG icon components are appropriate; no new dependency is required.
- Folder reveal is driven by the current tree and must survive a tree refresh while the panel opens. Missing folders do not leave dead shortcuts.
- Verify light and dark themes, English and Spanish labels, 170 px through wide desktop Workspace widths, collapsed rail, keyboard operation, and the mobile stacked layout. Check that New scenario and Add files still perform their current actions.
