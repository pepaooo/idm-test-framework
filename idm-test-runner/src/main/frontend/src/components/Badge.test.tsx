import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Badge } from './Badge';

describe('Badge', () => {
  it.each([
    ['ok', '✓', 'OK'],
    ['warn', '!', 'Warning'],
    ['missing', '✕', 'Missing'],
    ['finding', '✕', 'Finding'],
    ['error', '✕', 'Setup problem'],
  ] as const)('%s shows %s with the label "%s"', (status, symbol, label) => {
    render(<Badge status={status} />);
    const badge = screen.getByRole('img', { name: label });
    expect(badge).toHaveTextContent(symbol);
    expect(badge).toHaveClass('badge', `badge-${status}`);
  });

  it('takes a label of its own', () => {
    render(<Badge status="ok" label="Lab ready" />);
    expect(screen.getByRole('img', { name: 'Lab ready' })).toHaveTextContent('✓');
  });

  it('a finding is not coloured as an error', () => {
    render(
      <>
        <Badge status="finding" />
        <Badge status="error" />
      </>,
    );
    expect(screen.getByRole('img', { name: 'Finding' })).not.toHaveClass('badge-error');
  });
});
