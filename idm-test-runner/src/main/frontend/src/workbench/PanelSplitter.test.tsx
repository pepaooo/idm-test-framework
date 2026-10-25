import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { PanelSplitter } from './PanelSplitter';

describe('PanelSplitter', () => {
  it('resizes a vertical divider by keyboard with bounds', () => {
    const onChange = vi.fn();
    render(<PanelSplitter orientation="vertical" label="Resize Workspace" value={200} min={100} max={300} onChange={onChange} />);
    const splitter = screen.getByRole('separator', { name: 'Resize Workspace' });
    expect(splitter).toHaveAttribute('aria-orientation', 'vertical');
    expect(splitter).toHaveAttribute('aria-valuenow', '200');
    fireEvent.keyDown(splitter, { key: 'ArrowRight' });
    expect(onChange).toHaveBeenLastCalledWith(216);
    fireEvent.keyDown(splitter, { key: 'Home' });
    expect(onChange).toHaveBeenLastCalledWith(100);
    fireEvent.keyDown(splitter, { key: 'End' });
    expect(onChange).toHaveBeenLastCalledWith(300);
  });

  it('resizes a horizontal divider by pointer and keyboard', () => {
    const onChange = vi.fn();
    render(<PanelSplitter orientation="horizontal" label="Resize Problems" value={200} min={100} max={300} onChange={onChange} />);
    const splitter = screen.getByRole('separator', { name: 'Resize Problems' });
    fireEvent.keyDown(splitter, { key: 'ArrowUp' });
    expect(onChange).toHaveBeenLastCalledWith(184);
    fireEvent.pointerDown(splitter, { pointerId: 1, clientY: 150 });
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 50 });
    expect(onChange).toHaveBeenLastCalledWith(300);
    fireEvent.pointerUp(window, { pointerId: 1 });
  });
});
