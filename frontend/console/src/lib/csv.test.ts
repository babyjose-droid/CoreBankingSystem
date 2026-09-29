import { describe, expect, it } from 'vitest';
import { csvDataRowCount, csvEscape, parseCsv, toCsv } from './csv';

describe('csvEscape', () => {
  it('neutralises formulas, including ones that start with a minus and a digit', () => {
    expect(csvEscape('=HYPERLINK("x")')).toBe(`"'=HYPERLINK(""x"")"`);
    expect(csvEscape("-1+cmd|' /C calc'!A0")).toBe("'-1+cmd|' /C calc'!A0");
    expect(csvEscape('@SUM(A1)')).toBe("'@SUM(A1)");
    expect(csvEscape('\t=1')).toBe("'\t=1");
  });

  it('keeps plain numbers, including negative amounts', () => {
    expect(csvEscape('-125.50')).toBe('-125.50');
    expect(csvEscape(-3)).toBe('-3');
    expect(csvEscape('1000')).toBe('1000');
  });

  it('quotes commas, quotes and line breaks', () => {
    expect(csvEscape('a,b')).toBe('"a,b"');
    expect(toCsv(['h'], [[null], ['x\ny']])).toBe('h\r\n\r\n"x\ny"\r\n');
  });
});

describe('parseCsv', () => {
  it('parses quoted fields, CRLF, BOM and skips blank lines', () => {
    expect(parseCsv('﻿a,b\r\n"x, y","say ""hi"""\r\n\r\n1,\n')).toEqual([
      ['a', 'b'],
      ['x, y', 'say "hi"'],
      ['1', ''],
    ]);
  });

  it('round-trips toCsv output and counts data rows', () => {
    const text = toCsv(['day', 'reason'], [['2026-09-01', 'A, B'], ['2026-09-02', 'line\nbreak']]);
    expect(parseCsv(text)).toEqual([['day', 'reason'], ['2026-09-01', 'A, B'], ['2026-09-02', 'line\nbreak']]);
    expect(csvDataRowCount(text)).toBe(2);
    expect(csvDataRowCount('')).toBe(0);
  });
});
