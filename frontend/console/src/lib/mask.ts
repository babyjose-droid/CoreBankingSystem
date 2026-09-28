export const PAN_PATTERN = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
export const MOBILE_PATTERN = /^[6-9][0-9]{9}$/;
export const PINCODE_PATTERN = /^[1-9][0-9]{5}$/;
export const IFSC_PATTERN = /^[A-Z]{4}0[A-Z0-9]{6}$/;
export const BRANCH_CODE_PATTERN = /^[A-Z0-9]{2,10}$/;
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** ABCDE1234F -> XXXXX1234X (first five letters and the check letter hidden). */
export function maskPan(pan: string | null | undefined): string | null {
  if (!pan) return null;
  const p = pan.trim().toUpperCase();
  if (p.length !== 10) return 'X'.repeat(p.length);
  return `XXXXX${p.slice(5, 9)}X`;
}

/** 9876543210 -> XXXXXX3210 */
export function maskMobile(mobile: string | null | undefined): string | null {
  if (!mobile) return null;
  const m = mobile.replace(/\D/g, '');
  if (m.length < 4) return 'X'.repeat(m.length);
  return 'X'.repeat(m.length - 4) + m.slice(-4);
}

/** Masks all but the last 4 characters of an account-like identifier. */
export function maskAccount(value: string | null | undefined): string | null {
  if (!value) return null;
  if (value.length <= 4) return value;
  return '•'.repeat(value.length - 4) + value.slice(-4);
}
