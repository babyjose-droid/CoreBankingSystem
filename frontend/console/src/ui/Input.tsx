import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react';
import { cx } from '../lib/cx';
import { Field } from './Field';

interface Common {
  label: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  labelHidden?: boolean;
  fieldClassName?: string;
}

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'>, Common {
  numeric?: boolean;
}

export function Input({ label, hint, error, required, numeric, className, labelHidden, fieldClassName, ...rest }: InputProps) {
  return (
    <Field label={label} hint={hint} error={error} required={required} labelHidden={labelHidden} className={fieldClassName}>
      {({ id, describedBy, invalid }) => (
        <input
          id={id}
          className={cx('input', numeric && 'input--num', className)}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          aria-required={required || undefined}
          inputMode={numeric ? 'decimal' : rest.inputMode}
          {...rest}
        />
      )}
    </Field>
  );
}

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'>, Common {
  options: SelectOption[];
  placeholder?: string;
}

export function Select({ label, hint, error, required, options, placeholder, className, labelHidden, fieldClassName, ...rest }: SelectProps) {
  return (
    <Field label={label} hint={hint} error={error} required={required} labelHidden={labelHidden} className={fieldClassName}>
      {({ id, describedBy, invalid }) => (
        <select
          id={id}
          className={cx('select', className)}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          aria-required={required || undefined}
          {...rest}
        >
          {placeholder !== undefined && <option value="">{placeholder}</option>}
          {options.map((o) => (
            <option key={o.value} value={o.value} disabled={o.disabled}>
              {o.label}
            </option>
          ))}
        </select>
      )}
    </Field>
  );
}

export interface TextareaProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'>, Common {}

export function Textarea({ label, hint, error, required, className, labelHidden, fieldClassName, ...rest }: TextareaProps) {
  return (
    <Field label={label} hint={hint} error={error} required={required} labelHidden={labelHidden} className={fieldClassName}>
      {({ id, describedBy, invalid }) => (
        <textarea
          id={id}
          className={cx('textarea', className)}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          aria-required={required || undefined}
          {...rest}
        />
      )}
    </Field>
  );
}

export function Checkbox({ label, ...rest }: Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & { label: ReactNode }) {
  return (
    <label className="checkbox">
      <input type="checkbox" {...rest} />
      <span>{label}</span>
    </label>
  );
}
