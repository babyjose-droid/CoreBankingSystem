/**
 * Money helpers. The API carries money as decimal strings ("100000.00"); never convert to a JS number
 * for arithmetic. Internally we use bigint in 1/10000 units (4 decimal places, matching the contract's Money pattern).
 */
export const MONEY_PATTERN = /^-?[0-9]+(\.[0-9]{1,4})?$/;
const SCALE = 4;
const FACTOR = 10n ** BigInt(SCALE);

export function isMoney(value: string): boolean {
  return MONEY_PATTERN.test(value.trim());
}

/** Parses a Money string into bigint units of 1/10000. Throws on invalid input. */
export function toUnits(value: string): bigint {
  const v = value.trim();
  if (!MONEY_PATTERN.test(v)) throw new Error(`Invalid money value: "${value}"`);
  const negative = v.startsWith('-');
  const [intPart, fracPart = ''] = (negative ? v.slice(1) : v).split('.');
  const units = BigInt(intPart) * FACTOR + BigInt(fracPart.padEnd(SCALE, '0'));
  return negative ? -units : units;
}

/** Like toUnits but returns 0n for empty/invalid input (useful for forms). */
export function toUnitsSafe(value: string | null | undefined): bigint {
  if (!value || !isMoney(value)) return 0n;
  return toUnits(value);
}

/** Formats units as a Money string with exactly `decimals` places (default 2), rounding half away from zero. */
export function fromUnits(units: bigint, decimals = 2): string {
  const negative = units < 0n;
  let abs = negative ? -units : units;
  const drop = 10n ** BigInt(SCALE - decimals);
  if (drop > 1n) {
    const rem = abs % drop;
    abs = abs / drop + (rem * 2n >= drop ? 1n : 0n);
  }
  const base = 10n ** BigInt(decimals);
  const intPart = abs / base;
  const frac = (abs % base).toString().padStart(decimals, '0');
  const body = decimals > 0 ? `${intPart}.${frac}` : `${intPart}`;
  return negative && abs !== 0n ? `-${body}` : body;
}

export function addMoney(...values: string[]): string {
  return fromUnits(values.reduce((acc, v) => acc + toUnits(v), 0n));
}

export function subtractMoney(a: string, b: string): string {
  return fromUnits(toUnits(a) - toUnits(b));
}

export function negateMoney(a: string): string {
  return fromUnits(-toUnits(a));
}

export function compareMoney(a: string, b: string): number {
  const d = toUnits(a) - toUnits(b);
  return d === 0n ? 0 : d > 0n ? 1 : -1;
}

export function isZero(value: string): boolean {
  return toUnits(value) === 0n;
}

/** Indian digit grouping: 1234567 -> 12,34,567 */
export function groupIndian(digits: string): string {
  if (digits.length <= 3) return digits;
  const last3 = digits.slice(-3);
  const rest = digits.slice(0, -3);
  return `${rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',')},${last3}`;
}

/**
 * Formats a Money string for display in en-IN: "100000" -> "₹1,00,000.00".
 * Uses string arithmetic only (no float), so very large values stay exact.
 */
export function formatINR(value: string, opts: { symbol?: boolean; decimals?: number } = {}): string {
  const { symbol = true, decimals = 2 } = opts;
  const normalized = fromUnits(toUnits(value), decimals);
  const negative = normalized.startsWith('-');
  const [intPart, frac] = (negative ? normalized.slice(1) : normalized).split('.');
  const grouped = groupIndian(intPart) + (frac !== undefined ? `.${frac}` : '');
  return `${negative ? '-' : ''}${symbol ? '₹' : ''}${grouped}`;
}
