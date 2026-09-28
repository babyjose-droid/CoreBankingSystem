import { describe, expect, it } from 'vitest';
import { addMoney, formatINR, fromUnits, groupIndian, isMoney, subtractMoney, toUnits } from './money';

describe('money', () => {
  it('formats en-IN with lakh/crore grouping and 2 decimals', () => {
    expect(formatINR('100000')).toBe('₹1,00,000.00');
    expect(formatINR('100000.00')).toBe('₹1,00,000.00');
    expect(formatINR('12345678.5')).toBe('₹1,23,45,678.50');
    expect(formatINR('999')).toBe('₹999.00');
    expect(formatINR('0')).toBe('₹0.00');
  });

  it('formats negatives and optional symbol', () => {
    expect(formatINR('-2500.75')).toBe('-₹2,500.75');
    expect(formatINR('1000', { symbol: false })).toBe('1,000.00');
  });

  it('rounds half away from zero when dropping to 2 decimals (no float error)', () => {
    expect(formatINR('0.005')).toBe('₹0.01');
    expect(formatINR('1.0049')).toBe('₹1.00');
    expect(formatINR('-0.005')).toBe('-₹0.01');
    // Beyond Number.MAX_SAFE_INTEGER stays exact
    expect(formatINR('98765432109876543.21')).toBe('₹98,76,54,32,10,98,76,543.21');
  });

  it('groups digits the Indian way', () => {
    expect(groupIndian('1234567')).toBe('12,34,567');
    expect(groupIndian('123')).toBe('123');
    expect(groupIndian('1000')).toBe('1,000');
  });

  it('does exact decimal arithmetic on strings', () => {
    expect(addMoney('0.10', '0.20')).toBe('0.30');
    expect(subtractMoney('100000.00', '99999.99')).toBe('0.01');
    expect(fromUnits(toUnits('1.2345'), 4)).toBe('1.2345');
  });

  it('validates the contract Money pattern', () => {
    expect(isMoney('100000.00')).toBe(true);
    expect(isMoney('-1.5')).toBe(true);
    expect(isMoney('1.23456')).toBe(false);
    expect(isMoney('1e5')).toBe(false);
    expect(() => toUnits('abc')).toThrow();
  });
});
