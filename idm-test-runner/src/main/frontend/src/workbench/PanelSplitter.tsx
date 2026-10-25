import { useEffect, useRef } from 'react';

interface Props {
  orientation: 'vertical' | 'horizontal';
  label: string;
  value: number;
  min: number;
  max: number;
  onChange(value: number): void;
  /** A right-hand rail grows when its divider moves left. */
  reverse?: boolean;
}

export function PanelSplitter({ orientation, label, value, min, max, onChange, reverse = false }: Props) {
  const drag = useRef<{ pointerId: number; coordinate: number; value: number } | null>(null);
  const latest = useRef({ orientation, min, max, onChange, reverse });
  latest.current = { orientation, min, max, onChange, reverse };
  useEffect(() => {
    const move = (event: PointerEvent) => {
      const started = drag.current;
      if (!started || event.pointerId !== started.pointerId) return;
      const options = latest.current;
      const coordinate = options.orientation === 'vertical' ? event.clientX : event.clientY;
      const sign = options.orientation === 'horizontal' || options.reverse ? -1 : 1;
      const next = started.value + sign * (coordinate - started.coordinate);
      options.onChange(Math.max(options.min, Math.min(options.max, Math.round(next))));
    };
    const stop = (event: PointerEvent) => {
      if (drag.current?.pointerId === event.pointerId) drag.current = null;
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
    window.addEventListener('pointercancel', stop);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
      window.removeEventListener('pointercancel', stop);
    };
  }, []);

  const clamp = (next: number) => onChange(Math.max(min, Math.min(max, next)));
  return (
    <div
      className={`panel-splitter panel-splitter-${orientation}`}
      role="separator"
      tabIndex={0}
      aria-label={label}
      aria-orientation={orientation}
      aria-valuenow={value}
      aria-valuemin={min}
      aria-valuemax={max}
      onPointerDown={(event) => {
        drag.current = {
          pointerId: event.pointerId,
          coordinate: orientation === 'vertical' ? event.clientX : event.clientY,
          value,
        };
        event.currentTarget.setPointerCapture?.(event.pointerId);
      }}
      onKeyDown={(event) => {
        const forward = orientation === 'vertical' ? 'ArrowRight' : 'ArrowDown';
        const backward = orientation === 'vertical' ? 'ArrowLeft' : 'ArrowUp';
        if (event.key === forward) clamp(value + (reverse ? -16 : 16));
        else if (event.key === backward) clamp(value + (reverse ? 16 : -16));
        else if (event.key === 'Home') clamp(min);
        else if (event.key === 'End') clamp(max);
        else return;
        event.preventDefault();
      }}
    />
  );
}
