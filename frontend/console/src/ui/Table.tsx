import type { Key, ReactNode } from 'react';
import { cx } from '../lib/cx';

export interface Column<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  numeric?: boolean;
  className?: string;
  width?: string | number;
}

export interface TableProps<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => Key;
  caption?: ReactNode;
  captionHidden?: boolean;
  onRowClick?: (row: T) => void;
  rowLabel?: (row: T) => string;
  rowClassName?: (row: T) => string | undefined;
  empty?: ReactNode;
  footer?: ReactNode;
  selection?: {
    selected: Set<string>;
    onChange: (next: Set<string>) => void;
    idOf: (row: T) => string;
    isSelectable?: (row: T) => boolean;
    label: (row: T) => string;
  };
}

export function Table<T>({ columns, rows, rowKey, caption, captionHidden, onRowClick, rowLabel, rowClassName, empty, footer, selection }: TableProps<T>) {
  const selectable = selection ? rows.filter((r) => selection.isSelectable?.(r) ?? true) : [];
  const allSelected = !!selection && selectable.length > 0 && selectable.every((r) => selection.selected.has(selection.idOf(r)));
  return (
    <div className="table-wrap">
      <table className="table">
        {caption && <caption className={captionHidden ? 'sr-only' : undefined}>{caption}</caption>}
        <thead>
          <tr>
            {selection && (
              <th style={{ width: 32 }}>
                <input
                  type="checkbox"
                  aria-label="Select all"
                  checked={allSelected}
                  disabled={selectable.length === 0}
                  onChange={(e) => {
                    const next = new Set(selection.selected);
                    for (const r of selectable) {
                      if (e.target.checked) next.add(selection.idOf(r));
                      else next.delete(selection.idOf(r));
                    }
                    selection.onChange(next);
                  }}
                />
              </th>
            )}
            {columns.map((c) => (
              <th key={c.key} scope="col" className={cx(c.numeric && 'num', c.className)} style={c.width ? { width: c.width } : undefined}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={columns.length + (selection ? 1 : 0)}>{empty ?? <div className="empty">No records</div>}</td>
            </tr>
          )}
          {rows.map((r) => {
            const id = selection?.idOf(r);
            return (
              <tr
                key={rowKey(r)}
                className={cx(onRowClick && 'is-clickable', id && selection?.selected.has(id) && 'is-selected', rowClassName?.(r))}
                onClick={onRowClick ? () => onRowClick(r) : undefined}
                onKeyDown={
                  onRowClick
                    ? (e) => {
                        if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
                          e.preventDefault();
                          onRowClick(r);
                        }
                      }
                    : undefined
                }
                tabIndex={onRowClick ? 0 : undefined}
                aria-label={onRowClick && rowLabel ? rowLabel(r) : undefined}
              >
                {selection && (
                  <td onClick={(e) => e.stopPropagation()}>
                    <input
                      type="checkbox"
                      aria-label={selection.label(r)}
                      checked={!!id && selection.selected.has(id)}
                      disabled={!(selection.isSelectable?.(r) ?? true)}
                      onChange={(e) => {
                        const next = new Set(selection.selected);
                        if (e.target.checked) next.add(id!);
                        else next.delete(id!);
                        selection.onChange(next);
                      }}
                    />
                  </td>
                )}
                {columns.map((c) => (
                  <td key={c.key} className={cx(c.numeric && 'num', c.className)}>
                    {c.render(r)}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
        {footer && <tfoot>{footer}</tfoot>}
      </table>
    </div>
  );
}
