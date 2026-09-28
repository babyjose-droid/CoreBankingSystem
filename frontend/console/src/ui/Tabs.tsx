import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react';

export interface TabDef {
  id: string;
  label: ReactNode;
  content: ReactNode;
}

/** WAI-ARIA tabs with roving focus (arrow keys, Home/End). */
export function Tabs({ tabs, active, onChange, label }: { tabs: TabDef[]; active: string; onChange: (id: string) => void; label: string }) {
  const base = useId();
  const refs = useRef<Array<HTMLButtonElement | null>>([]);
  const idx = Math.max(0, tabs.findIndex((t) => t.id === active));
  const onKey = (e: KeyboardEvent) => {
    let next = idx;
    if (e.key === 'ArrowRight') next = (idx + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') next = (idx - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    else return;
    e.preventDefault();
    onChange(tabs[next].id);
    refs.current[next]?.focus();
  };
  return (
    <div className="tabs">
      <div role="tablist" aria-label={label} className="tabs__list" onKeyDown={onKey}>
        {tabs.map((t, i) => (
          <button
            key={t.id}
            ref={(el) => {
              refs.current[i] = el;
            }}
            role="tab"
            type="button"
            id={`${base}-tab-${t.id}`}
            aria-selected={t.id === tabs[idx].id}
            aria-controls={`${base}-panel-${t.id}`}
            tabIndex={t.id === tabs[idx].id ? 0 : -1}
            className="tabs__tab"
            onClick={() => onChange(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tabs.map((t) => (
        <div
          key={t.id}
          role="tabpanel"
          id={`${base}-panel-${t.id}`}
          aria-labelledby={`${base}-tab-${t.id}`}
          hidden={t.id !== tabs[idx].id}
          className="tabs__panel"
          tabIndex={0}
        >
          {t.content}
        </div>
      ))}
    </div>
  );
}
