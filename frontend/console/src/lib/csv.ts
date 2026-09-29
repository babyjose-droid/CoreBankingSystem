export type CsvCell = string | number | boolean | null | undefined;

export function csvEscape(value: CsvCell): string {
  if (value === null || value === undefined) return '';
  const s = String(value);
  // Neutralise spreadsheet formula injection, then quote if needed.
  // Only a value that is entirely a plain number may start with '-' (e.g. -125.50); tab and CR also start formulas.
  const safe = /^[=+\-@\t\r]/.test(s) && !/^-?\d+(\.\d+)?$/.test(s) ? `'${s}` : s;
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

export function toCsv(headers: string[], rows: CsvCell[][]): string {
  return [headers, ...rows].map((r) => r.map(csvEscape).join(',')).join('\r\n') + '\r\n';
}

/** Triggers a client-side download of a CSV file. */
export function downloadCsv(filename: string, headers: string[], rows: CsvCell[][]): void {
  const blob = new Blob(['﻿' + toCsv(headers, rows)], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/**
 * Parses CSV text (RFC 4180: quoted fields, doubled quotes, CRLF or LF). A leading BOM is ignored and blank lines are
 * skipped. Returns rows of raw cell strings (not trimmed).
 */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  const src = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  const endRow = () => {
    row.push(cell);
    if (row.length > 1 || row[0].trim() !== '') rows.push(row);
    row = [];
    cell = '';
  };
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"') {
        if (src[i + 1] === '"') {
          cell += '"';
          i++;
        } else quoted = false;
      } else cell += ch;
    } else if (ch === '"' && cell === '') quoted = true;
    else if (ch === ',') {
      row.push(cell);
      cell = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && src[i + 1] === '\n') i++;
      endRow();
    } else cell += ch;
  }
  if (cell !== '' || row.length > 0) endRow();
  return rows;
}

/** Number of data rows (excluding the header) in CSV text. */
export function csvDataRowCount(text: string): number {
  return Math.max(0, parseCsv(text).length - 1);
}
