import { afterEach, describe, expect, it } from 'vitest';
import { DEFAULT_LAYOUT, clampPanelLayout, readPanelLayout, writePanelLayout } from './panelLayout';

afterEach(() => window.localStorage.clear());

describe('workbench panel layout preferences', () => {
  it('restores only this workspace', () => {
    expect(readPanelLayout('/a')).toMatchObject({ workspaceWidth: 230, runWidth: 320, problemsHeight: 180 });
    writePanelLayout('/a', { ...DEFAULT_LAYOUT, workspaceWidth: 360, workspaceCollapsed: true });
    expect(readPanelLayout('/a')).toMatchObject({ workspaceWidth: 360, workspaceCollapsed: true });
    expect(readPanelLayout('/b')).toEqual(DEFAULT_LAYOUT);
  });

  it('ignores bad storage', () => {
    window.localStorage.setItem('idmtest.layout.v1:/a', '{broken');
    expect(readPanelLayout('/a')).toEqual(DEFAULT_LAYOUT);
    window.localStorage.setItem('idmtest.layout.v1:/a', JSON.stringify({ workspaceWidth: -10 }));
    expect(readPanelLayout('/a')).toEqual(DEFAULT_LAYOUT);
  });

  it('clamps saved widths at narrow viewports without erasing the preference', () => {
    const saved = { ...DEFAULT_LAYOUT, workspaceWidth: 600, runWidth: 500, problemsHeight: 500 };
    writePanelLayout('/a', saved);
    const displayed = clampPanelLayout(saved, 800, 600);
    expect(displayed.workspaceWidth + displayed.runWidth).toBeLessThanOrEqual(480);
    expect(displayed.problemsHeight).toBeLessThanOrEqual(410);
    expect(readPanelLayout('/a')).toEqual(saved);
  });
});
