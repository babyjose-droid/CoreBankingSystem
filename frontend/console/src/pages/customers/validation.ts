import { ageOn, ISO_DATE } from '../../lib/dates';
import { EMAIL_PATTERN, MOBILE_PATTERN, PAN_PATTERN, PINCODE_PATTERN } from '../../lib/mask';

export interface Basics {
  customerType: 'INDIVIDUAL' | 'NON_INDIVIDUAL';
  homeBranch: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  mobile: string;
  pan: string;
}

export type Errors<T> = Partial<Record<keyof T, string>>;

export function validateBasics(b: Basics, businessDate: string): Errors<Basics> {
  const e: Errors<Basics> = {};
  if (!b.homeBranch) e.homeBranch = 'Select a branch';
  if (!b.firstName.trim()) e.firstName = b.customerType === 'INDIVIDUAL' ? 'First name is required' : 'Name is required';
  if (!b.dateOfBirth || !ISO_DATE.test(b.dateOfBirth)) {
    e.dateOfBirth = b.customerType === 'INDIVIDUAL' ? 'Date of birth is required' : 'Date of incorporation is required';
  } else if (b.dateOfBirth > businessDate) {
    e.dateOfBirth = 'Cannot be after the business date';
  } else if (b.customerType === 'INDIVIDUAL' && ageOn(b.dateOfBirth, businessDate) < 18) {
    e.dateOfBirth = 'Customer must be at least 18 years old';
  }
  if (!MOBILE_PATTERN.test(b.mobile)) e.mobile = 'Enter a 10-digit mobile number starting with 6-9';
  if (b.pan && !PAN_PATTERN.test(b.pan)) e.pan = 'PAN must be 5 letters, 4 digits, 1 letter (e.g. ABCDE1234F)';
  return e;
}

export interface Details {
  middleName: string;
  gender: '' | 'FEMALE' | 'MALE' | 'OTHER';
  email: string;
  line1: string;
  line2: string;
  city: string;
  stateCode: string;
  pincode: string;
}

export function validateDetails(d: Details): Errors<Details> {
  const e: Errors<Details> = {};
  if (d.email && !EMAIL_PATTERN.test(d.email)) e.email = 'Enter a valid email address';
  if (d.pincode && !PINCODE_PATTERN.test(d.pincode)) e.pincode = 'PIN code must be 6 digits and not start with 0';
  return e;
}
