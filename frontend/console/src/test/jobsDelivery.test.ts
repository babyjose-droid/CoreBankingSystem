import { describe, expect, it } from 'vitest';
import { deliveryNote } from '../pages/platform/JobsPage';

describe('scheduled report delivery note', () => {
  it('says what happened without naming anyone', () => {
    expect(deliveryNote({ delivery: 'SENT', recipientsSent: 2, recipientsLeftOut: 1 })).toBe('Report e-mailed to 2 internal recipient(s); 1 recipient(s) outside the internal domains left out');
    expect(deliveryNote({ delivery: 'NOT_CONFIGURED' })).toMatch(/not configured/);
    expect(deliveryNote({ delivery: 'NO_INTERNAL_RECIPIENT', recipientsLeftOut: 3 })).toMatch(/no recipient on an internal domain; 3 recipient/);
    expect(deliveryNote({ delivery: 'NOT_EMAILED' })).toMatch(/credit-bureau file is never e-mailed/);
    expect(deliveryNote(null)).toBe('');
  });
});
