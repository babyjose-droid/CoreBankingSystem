import { Checkbox } from '../../ui';

/** Multi-select as a group of checkboxes (keyboard and screen-reader friendly, no extra dependency). */
export function Checklist({
  legend,
  options,
  selected,
  onChange,
  disabled,
  error,
}: {
  legend: string;
  options: Array<{ value: string; label: string; disabled?: boolean }>;
  selected: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
  error?: string | null;
}) {
  return (
    <div className="field">
      <fieldset className="checklist" disabled={disabled} aria-invalid={!!error || undefined}>
        <legend>{legend}</legend>
        {options.length === 0 && <span className="muted">None available</span>}
        {options.map((o) => (
          <Checkbox
            key={o.value}
            label={o.label}
            checked={selected.includes(o.value)}
            disabled={o.disabled}
            onChange={(e) => onChange(e.target.checked ? [...selected, o.value] : selected.filter((x) => x !== o.value))}
          />
        ))}
      </fieldset>
      {error && (
        <span className="field__error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}
