import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setLanguage } from '../i18n';
import { treeOf } from '../test/workbenchServer';
import { WorkspaceFilePicker } from './WorkspaceFilePicker';

const files = treeOf([
  'scenarios/users/b.yaml',
  'scenarios/admin/b.yaml',
  'config/application-lab.yml',
  'README.md',
  'secrets.env',
]);

afterEach(() => setLanguage('en', false));

describe('Workspace file picker', () => {
  it('chooses any workspace file, including one inside a folded folder', async () => {
    const onOpen = vi.fn();
    render(<WorkspaceFilePicker tree={files} active={null} dirty={new Set()} onOpen={onOpen} />);
    await userEvent.click(screen.getByRole('button', { name: 'Open workspace file' }));
    const option = screen.getByRole('option', { name: 'scenarios/users/b.yaml' });
    expect(option).toBeVisible();
    await userEvent.click(option);
    expect(onOpen).toHaveBeenCalledWith('scenarios/users/b.yaml');
  });

  it('distinguishes same names by path and supports search and Enter', async () => {
    const onOpen = vi.fn();
    render(<WorkspaceFilePicker tree={files} active="scenarios/admin/b.yaml" dirty={new Set(['scenarios/admin/b.yaml'])} onOpen={onOpen} />);
    await userEvent.click(screen.getByRole('button', { name: 'Open workspace file' }));
    const list = screen.getByRole('listbox', { name: 'Workspace files' });
    expect(within(list).getByRole('option', { name: 'scenarios/admin/b.yaml' })).toHaveAttribute('aria-selected', 'true');
    expect(within(list).getByRole('img', { name: 'unsaved' })).toBeInTheDocument();
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find a workspace file' }), 'users/b');
    expect(within(list).getByRole('option', { name: 'scenarios/users/b.yaml' })).toBeVisible();
    expect(within(list).queryByRole('option', { name: 'scenarios/admin/b.yaml' })).not.toBeInTheDocument();
    await userEvent.keyboard('{Enter}');
    expect(onOpen).toHaveBeenCalledWith('scenarios/users/b.yaml');
  });

  it('keeps secrets locked and closes with Escape', async () => {
    const onOpen = vi.fn();
    render(<WorkspaceFilePicker tree={files} active={null} dirty={new Set()} onOpen={onOpen} />);
    const trigger = screen.getByRole('button', { name: 'Open workspace file' });
    await userEvent.click(trigger);
    const secret = screen.getByRole('option', { name: /secrets\.env/ });
    expect(secret).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(secret);
    expect(onOpen).not.toHaveBeenCalled();
    await userEvent.keyboard('{Escape}');
    expect(trigger).toHaveFocus();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('tracks tree refresh while open', async () => {
    const onOpen = vi.fn();
    const page = render(<WorkspaceFilePicker tree={files} active={null} dirty={new Set()} onOpen={onOpen} />);
    await userEvent.click(screen.getByRole('button', { name: 'Open workspace file' }));
    expect(screen.getByRole('option', { name: 'scenarios/users/b.yaml' })).toBeInTheDocument();
    page.rerender(<WorkspaceFilePicker tree={treeOf(['README.md'])} active={null} dirty={new Set()} onOpen={onOpen} />);
    expect(screen.queryByRole('option', { name: 'scenarios/users/b.yaml' })).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'README.md' })).toBeInTheDocument();
  });

  it('keeps the chooser inside a narrow editor', async () => {
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { width: this.classList.contains('editor-pane') ? 320 : 0 } as DOMRect;
    });
    try {
      render(<div className="editor-pane"><WorkspaceFilePicker tree={files} active={null} dirty={new Set()} onOpen={vi.fn()} /></div>);
      await userEvent.click(screen.getByRole('button', { name: 'Open workspace file' }));
      expect(screen.getByRole('listbox').parentElement).toHaveStyle({ width: '308px' });
    } finally {
      rect.mockRestore();
    }
  });

  it('announces and scrolls to the keyboard-highlighted result', async () => {
    const scroll = vi.fn();
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = scroll;
    try {
      render(<WorkspaceFilePicker tree={files} active={null} dirty={new Set()} onOpen={vi.fn()} />);
      await userEvent.click(screen.getByRole('button', { name: 'Open workspace file' }));
      const search = screen.getByRole('searchbox', { name: 'Find a workspace file' });
      const beforeMove = scroll.mock.calls.length;
      await userEvent.type(search, '{ArrowDown}');
      const options = screen.getAllByRole('option');
      expect(search).toHaveAttribute('aria-activedescendant', options[1].id);
      expect(options[1]).toHaveAttribute('aria-selected', 'true');
      expect(scroll.mock.calls.length).toBeGreaterThan(beforeMove);
    } finally {
      Element.prototype.scrollIntoView = original;
    }
  });
});
