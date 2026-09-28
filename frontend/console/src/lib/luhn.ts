/** Luhn (mod 10) check digit for a numeric string. */
export function luhnCheckDigit(digits: string): number {
  if (!/^\d+$/.test(digits)) throw new Error('Luhn input must be digits');
  let sum = 0;
  // Double every second digit counting from the right of the payload (the check digit goes after it).
  for (let i = 0; i < digits.length; i++) {
    let d = Number(digits[digits.length - 1 - i]);
    if (i % 2 === 0) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
  }
  return (10 - (sum % 10)) % 10;
}

export function isLuhnValid(number: string): boolean {
  if (!/^\d{2,}$/.test(number)) return false;
  return luhnCheckDigit(number.slice(0, -1)) === Number(number.slice(-1));
}

/** Customer number: series prefix + zero-padded sequence (9 digits) + Luhn check digit. */
export function customerNumber(prefix: string, seq: number): string {
  const payload = prefix + String(seq).padStart(9, '0');
  return payload + luhnCheckDigit(payload);
}
