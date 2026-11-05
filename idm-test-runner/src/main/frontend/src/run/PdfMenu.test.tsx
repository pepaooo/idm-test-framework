import { afterEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PdfMenu } from './PdfMenu';
import { setPageWorkspace } from '../api/client';

afterEach(() => setPageWorkspace(null));

describe('PdfMenu', () => {
  it('offers the summary and the detailed PDF as download links carrying the workspace', async () => {
    setPageWorkspace('/work/lab space');
    render(<PdfMenu runId="20261019-143205-lab" />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Download PDF/ }));
    const ws = encodeURIComponent('/work/lab space');
    const summary = screen.getByRole('link', { name: 'Summary' });
    const detailed = screen.getByRole('link', { name: 'Detailed' });
    expect(summary).toHaveAttribute('href', `/reports/20261019-143205-lab/report.pdf?kind=summary&workspace=${ws}`);
    expect(detailed).toHaveAttribute('href', `/reports/20261019-143205-lab/report.pdf?kind=detailed&workspace=${ws}`);
    expect(summary).toHaveAttribute('download');
    expect(detailed).toHaveAttribute('download');
  });

  it('closes on Escape and returns the focus to the button', async () => {
    render(<PdfMenu runId="r1" />);
    const button = screen.getByRole('button', { name: /Download PDF/ });
    await userEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });
});
