export interface PanelLayout {
  workspaceWidth: number;
  runWidth: number;
  problemsHeight: number;
  workspaceCollapsed: boolean;
  runCollapsed: boolean;
  problemsCollapsed: boolean;
}

export const DEFAULT_LAYOUT: PanelLayout = {
  workspaceWidth: 230,
  runWidth: 320,
  problemsHeight: 180,
  workspaceCollapsed: false,
  runCollapsed: false,
  problemsCollapsed: false,
};

const preferenceKey = (workspacePath: string) => `idmtest.layout.v1:${workspacePath}`;

export function readPanelLayout(workspacePath: string): PanelLayout {
  const stored = readPreference(preferenceKey(workspacePath));
  if (!stored) return { ...DEFAULT_LAYOUT };
  try {
    const value: unknown = JSON.parse(stored);
    if (!value || typeof value !== 'object') return { ...DEFAULT_LAYOUT };
    const layout = value as Record<keyof PanelLayout, unknown>;
    if (
      !Number.isFinite(layout.workspaceWidth) || Number(layout.workspaceWidth) <= 0 ||
      !Number.isFinite(layout.runWidth) || Number(layout.runWidth) <= 0 ||
      !Number.isFinite(layout.problemsHeight) || Number(layout.problemsHeight) <= 0 ||
      typeof layout.workspaceCollapsed !== 'boolean' ||
      typeof layout.runCollapsed !== 'boolean' ||
      typeof layout.problemsCollapsed !== 'boolean'
    ) return { ...DEFAULT_LAYOUT };
    return layout as unknown as PanelLayout;
  } catch {
    return { ...DEFAULT_LAYOUT };
  }
}

export function writePanelLayout(workspacePath: string, layout: PanelLayout): void {
  writePreference(preferenceKey(workspacePath), JSON.stringify(layout));
}

export function clampPanelLayout(layout: PanelLayout, viewportWidth: number, viewportHeight: number): PanelLayout {
  const minTree = layout.workspaceCollapsed ? 36 : 170;
  const minRun = layout.runCollapsed ? 44 : 220;
  const available = Math.max(minTree + minRun, viewportWidth - 320);
  let tree = layout.workspaceCollapsed ? 36 : Math.max(minTree, layout.workspaceWidth);
  let run = layout.runCollapsed ? 44 : Math.max(minRun, layout.runWidth);
  if (tree + run > available) {
    const flexible = Math.max(0, available - minTree - minRun);
    const treeExtra = tree - minTree;
    const runExtra = run - minRun;
    const share = treeExtra + runExtra ? treeExtra / (treeExtra + runExtra) : 0.5;
    tree = minTree + Math.round(flexible * share);
    run = minRun + flexible - Math.round(flexible * share);
  }
  return {
    ...layout,
    workspaceWidth: tree,
    runWidth: run,
    problemsHeight: Math.max(90, Math.min(layout.problemsHeight, Math.max(90, viewportHeight - 190))),
  };
}
import { useCallback, useEffect, useState } from 'react';
import { readPreference, writePreference } from '../storage';


export function usePanelLayout(workspacePath: string) {
  const [layout, setLayout] = useState<PanelLayout>(() => readPanelLayout(workspacePath));
  useEffect(() => setLayout(readPanelLayout(workspacePath)), [workspacePath]);
  const update = useCallback((patch: Partial<PanelLayout>) => {
    setLayout((current) => {
      const next = { ...current, ...patch };
      writePanelLayout(workspacePath, next);
      return next;
    });
  }, [workspacePath]);
  const reset = useCallback(() => {
    const next = { ...DEFAULT_LAYOUT };
    writePanelLayout(workspacePath, next);
    setLayout(next);
  }, [workspacePath]);
  return { layout, update, reset };
}
