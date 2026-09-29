import { useId, useRef, useState, type ReactNode } from 'react';
import { isApiError } from '../api/errors';
import { csvDataRowCount, downloadCsv } from '../lib/csv';
import { Button } from './Button';
import { Banner } from './Display';
import { ErrorBanner } from './ErrorBanner';

/** The subset of a react-query mutation the upload needs. */
export interface CsvUploadMutation<T> {
  mutateAsync: (text: string) => Promise<T>;
  isPending: boolean;
  error: unknown;
  reset: () => void;
}

export interface CsvUploadProps<T> {
  /** Accessible name of the file input, e.g. "Holiday file". */
  label: string;
  /** Header row of the template; `required` columns first, then optional ones. */
  columns: string[];
  /** Optional example rows for the template. */
  exampleRows?: string[][];
  templateName: string;
  maxRows?: number;
  hint?: ReactNode;
  mutation: CsvUploadMutation<T>;
  onUploaded: (result: T, rows: number) => void;
}

function readText(file: File): Promise<string> {
  if (typeof file.text === 'function') return file.text();
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result ?? ''));
    r.onerror = () => reject(r.error);
    r.readAsText(file);
  });
}

/**
 * CSV upload: pick a file, see how many data rows it has, then send it as text/csv. Server problems (413 too large,
 * 422 with per-row errors) are shown in full so the user can fix the file.
 */
export function CsvUpload<T>({ label, columns, exampleRows = [], templateName, maxRows, hint, mutation, onUploaded }: CsvUploadProps<T>) {
  const inputId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<{ name: string; text: string; rows: number } | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);

  const pick = async (f: File | undefined) => {
    mutation.reset();
    setLocalError(null);
    setFile(null);
    if (!f) return;
    const text = await readText(f);
    const rows = csvDataRowCount(text);
    if (rows === 0) setLocalError(`${f.name} has no data rows below the header.`);
    setFile({ name: f.name, text, rows });
  };

  const send = async () => {
    if (!file || file.rows === 0) return;
    try {
      const result = await mutation.mutateAsync(file.text);
      onUploaded(result, file.rows);
      setFile(null);
      if (inputRef.current) inputRef.current.value = '';
    } catch {
      /* shown from mutation.error */
    }
  };

  const fieldErrors = isApiError(mutation.error) ? mutation.error.fieldErrors : [];
  return (
    <div className="csv-upload stack">
      <div className="row" style={{ alignItems: 'flex-end' }}>
        <div className="field">
          <label htmlFor={inputId} className="field__label">
            {label}
          </label>
          <input id={inputId} ref={inputRef} type="file" accept=".csv,text/csv" className="input" onChange={(e) => void pick(e.target.files?.[0])} />
        </div>
        <Button variant="ghost" size="sm" onClick={() => downloadCsv(templateName, columns, exampleRows)}>
          Download template
        </Button>
      </div>
      <p className="muted" style={{ margin: 0, fontSize: 12 }}>
        Columns: <span className="mono">{columns.join(', ')}</span>
        {maxRows ? ` · up to ${maxRows.toLocaleString('en-IN')} rows` : ''}
        {hint ? <> · {hint}</> : null}
      </p>
      {file && file.rows > 0 && (
        <div className="row" style={{ alignItems: 'center' }}>
          <span data-testid="csv-row-count">
            <strong>{file.rows}</strong> data row{file.rows === 1 ? '' : 's'} in <span className="mono">{file.name}</span>
          </span>
          {maxRows !== undefined && file.rows > maxRows && <Banner tone="warn">More than {maxRows.toLocaleString('en-IN')} rows; the server will refuse this file.</Banner>}
          <Button variant="primary" loading={mutation.isPending} onClick={() => void send()}>
            Upload {file.rows} row{file.rows === 1 ? '' : 's'}
          </Button>
        </div>
      )}
      {localError && <Banner tone="danger">{localError}</Banner>}
      <ErrorBanner error={mutation.error} />
      {fieldErrors.length > 1 && (
        <ul className="csv-upload__errors" aria-label="Problems in the file">
          {fieldErrors.slice(0, 20).map((e, i) => (
            <li key={i}>{e.message}</li>
          ))}
          {fieldErrors.length > 20 && <li className="muted">…and {fieldErrors.length - 20} more</li>}
        </ul>
      )}
    </div>
  );
}
