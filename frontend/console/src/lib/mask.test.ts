import { describe, expect, it } from 'vitest';
import { toCsv } from './csv';
import { addDays, ageOn, financialYearStart, formatDate } from './dates';
import { customerNumber, isLuhnValid, luhnCheckDigit } from './luhn';
import { maskMobile, maskPan } from './mask';

describe('masking', () => {
  it('masks PAN as XXXXX + 4 digits + X', () => {
    expect(maskPan('ABCDE1234F')).toBe('XXXXX1234X');
    expect(maskPan('abcde9876z')).toBe('XXXXX9876X');
    expect(maskPan(null)).toBeNull();
  });
  it('masks mobile leaving the last 4 digits', () => {
    expect(maskMobile('9876543210')).toBe('XXXXXX3210');
    expect(maskMobile(undefined)).toBeNull();
  });
});

describe('luhn customer numbers', () => {
  it('computes the standard Luhn check digit', () => {
    expect(luhnCheckDigit('7992739871')).toBe(3); // classic example 79927398713
    expect(isLuhnValid('79927398713')).toBe(true);
    expect(isLuhnValid('79927398710')).toBe(false);
  });
  it('builds 9001 + 9-digit sequence + check digit', () => {
    const n = customerNumber('9001', 42);
    expect(n).toMatch(/^9001000000042\d$/);
    expect(n).toHaveLength(14);
    expect(isLuhnValid(n)).toBe(true);
  });
});

describe('dates and csv', () => {
  it('computes age on a business date', () => {
    expect(ageOn('2008-06-30', '2026-06-30')).toBe(18);
    expect(ageOn('2008-07-01', '2026-06-30')).toBe(17);
  });
  it('formats and shifts ISO dates without time-zone drift', () => {
    expect(formatDate('2026-06-30')).toBe('30 Jun 2026');
    expect(formatDate('2026-06-30', true)).toBe('Tue, 30 Jun 2026');
    expect(addDays('2026-06-30', 1)).toBe('2026-07-01');
    expect(financialYearStart('2026-03-31')).toBe('2025-04-01');
    expect(financialYearStart('2026-06-30')).toBe('2026-04-01');
  });
  it('escapes CSV cells and neutralises formulas', () => {
    expect(toCsv(['a', 'b'], [['x,y', '=SUM(A1)'], ['-12.50', 'q"t']])).toBe('a,b\r\n"x,y",\'=SUM(A1)\r\n-12.50,"q""t"\r\n');
  });
});
