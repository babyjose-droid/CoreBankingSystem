import { useId, type ReactNode } from 'react';

export interface FieldProps {
  label: ReactNode;
  required?: boolean;
  hint?: ReactNode;
  error?: string | null;
  /** Render prop receives the ids to wire onto the control. */
  children: (ids: { id: string; describedBy: string | undefined; invalid: boolean }) => ReactNode;
  className?: string;
  labelHidden?: boolean;
}

export function Field({ label, required, hint, error, children, className, labelHidden }: FieldProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errId = error ? `${id}-err` : undefined;
  const describedBy = [hintId, errId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={['field', className].filter(Boolean).join(' ')}>
      <label htmlFor={id} className={labelHidden ? 'sr-only' : 'field__label'}>
        {label}
        {required && (
          <span className="field__req" aria-hidden="true">
            *
          </span>
        )}
      </label>
      {children({ id, describedBy, invalid: !!error })}
      {hint && !error && (
        <span id={hintId} className="field__hint">
          {hint}
        </span>
      )}
      {error && (
        <span id={errId} className="field__error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}
