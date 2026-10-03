import { afterEach, describe, expect, it } from 'vitest';
import { findDemoUser, mockToken } from '../auth/demoUsers';
import { mockCall } from '../test/utils';
import { dltForm, placeholders } from './integrations';
import { createMockServer, type MockServer } from './server';

const approve = (server: MockServer, user: string, id: string) => mockCall(server, user, 'POST', `/api/v1/approvals/${id}/approve`, {});
const sendText = (server: MockServer, user: string, path: string, body: string, contentType = 'text/plain') =>
  server.fetch(new Request(`http://localhost${path}`, { method: 'POST', headers: { Authorization: `Bearer ${mockToken(user)}`, 'Content-Type': contentType }, body })).then(async (r) => ({ status: r.status, body: await r.json() }));

const maker = findDemoUser('maker')!;
const makerPerms = [...maker.permissions];
afterEach(() => {
  maker.permissions = [...makerPerms];
});

describe('providers', () => {
  it('keeps only the last four characters of a secret, and never shows it to the checker', async () => {
    const server = createMockServer();
    const SECRET = 'sk-CLAUDE-TEST-supersecret-9f3a';
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/integrations/providers', { kind: 'SMS', provider: 'GENERIC_HTTP' })).status).toBe(403);
    const short = await mockCall(server, 'admin', 'POST', '/api/v1/integrations/providers', { kind: 'SMS', provider: 'GENERIC_HTTP', settings: { url: 'https://sms.partner.example/send' }, secrets: { apiKey: 'short' } });
    expect(short.status).toBe(422);
    const missing = await mockCall(server, 'admin', 'POST', '/api/v1/integrations/providers', { kind: 'SMS', provider: 'GENERIC_HTTP', settings: { url: 'https://sms.partner.example/send' } });
    expect(missing.body.detail).toContain("secret 'apiKey' is required");
    const p = await mockCall(server, 'admin', 'POST', '/api/v1/integrations/providers', { kind: 'SMS', provider: 'GENERIC_HTTP', settings: { url: 'https://sms.partner.example/send' }, secrets: { apiKey: SECRET } });
    expect(p.status).toBe(202);
    expect(p.body.proposed).toMatchObject({ secretsChanged: ['apiKey'], secretHints: { apiKey: '9f3a' }, verified: false });
    expect((await approve(server, 'checker', p.body.id)).body).toMatchObject({ status: 'APPROVED', appliedRef: 'SMS v2' });
    const active = (await mockCall(server, 'auditor', 'GET', '/api/v1/integrations/providers')).body.find((x: { kind: string }) => x.kind === 'SMS');
    expect(active).toMatchObject({ provider: 'GENERIC_HTTP', version: 2, secrets: { apiKey: { set: true, last4: '9f3a' } } });
    expect(JSON.stringify(server.db, (_k, v) => (v instanceof Set || v instanceof Map ? [...v] : v))).not.toContain(SECRET);
    // a provider the deployment has not enabled cannot be configured
    expect((await mockCall(server, 'admin', 'POST', '/api/v1/integrations/providers', { kind: 'PAYOUT', provider: 'EASEBUZZ', secrets: { key: '12345678', salt: '12345678' } })).status).toBe(409);
  });
});

describe('payouts', () => {
  it('a failed payout gets its disbursement reversal proposed; rejecting it parks the payout for a new attempt', async () => {
    const server = createMockServer();
    const sent = server.db.integration.payouts.find((p) => p.status === 'SENT')!;
    const cb = await mockCall(server, 'admin', 'POST', '/api/v1/integrations/simulator/callbacks', { kind: 'payout', reference: sent.reference, status: 'FAILED', reasonCode: 'ACC_FROZEN', reason: 'Account frozen', eventId: 'evt-1' });
    expect(cb.body).toEqual({ eventId: 'evt-1', receipt: 'ACCEPTED' });
    expect((await mockCall(server, 'admin', 'POST', '/api/v1/integrations/simulator/callbacks', { kind: 'payout', reference: sent.reference, status: 'FAILED', eventId: 'evt-1' })).body.receipt).toBe('DUPLICATE');
    const view = (await mockCall(server, 'ops', 'GET', `/api/v1/payouts/${sent.id}`)).body;
    expect(view).toMatchObject({ status: 'FAILED', failureAction: 'PROPOSED', failureReason: 'Account frozen', needsAction: true });
    expect(view.beneficiaryAccountMasked).toMatch(/^X+\d{4}$/);
    const reversal = server.db.approvals.find((a) => a.approval.id === view.reversalApprovalId)!;
    expect(reversal.approval).toMatchObject({ entityType: 'LOAN_DISBURSEMENT_REVERSAL', maker: 'system', status: 'PENDING' });
    expect((await mockCall(server, 'ops', 'POST', `/api/v1/payouts/${sent.id}/retry`)).status).toBe(409);
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${reversal.approval.id}/reject`, { note: 'Borrower gave a new account' });
    expect(sent.failureAction).toBe('PARKED');
    const next = await mockCall(server, 'ops', 'POST', `/api/v1/payouts/${sent.id}/retry`);
    expect(next.body).toMatchObject({ status: 'SENT', attemptNo: 2 });
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/payouts/${sent.id}/retry`)).status).toBe(403);
  });

  it('approving the reversal settles the payout as reversed; the simulator is refused without integration:simulate', async () => {
    const server = createMockServer();
    const sent = server.db.integration.payouts.find((p) => p.status === 'SENT')!;
    expect((await mockCall(server, 'ops', 'POST', '/api/v1/integrations/simulator/callbacks', { kind: 'payout', reference: sent.reference, status: 'RETURNED' })).status).toBe(403);
    await mockCall(server, 'admin', 'POST', '/api/v1/integrations/simulator/callbacks', { kind: 'payout', reference: sent.reference, status: 'FAILED' });
    await approve(server, 'checker', sent.reversalApprovalId!);
    expect(sent).toMatchObject({ failureAction: 'REVERSED', needsAction: false });
  });

  it('validates a beneficiary and returns it masked', async () => {
    const server = createMockServer();
    const held = server.db.integration.payouts.find((p) => p.status === 'ON_HOLD')!;
    const path = `/api/v1/loans/${held.loanId}/payout-beneficiary`;
    expect((await mockCall(server, 'ops', 'PUT', path, { holderName: 'Esha CLAUDE-TEST', accountNumber: '123', ifsc: 'bad' })).body.errors).toHaveLength(2);
    expect((await mockCall(server, 'ops', 'PUT', path, { holderName: 'Esha CLAUDE-TEST', accountNumber: '5000000000', ifsc: 'HDFC0001234' })).body.detail).toContain('the bank did not confirm this account');
    const saved = await mockCall(server, 'ops', 'PUT', path, { holderName: 'Esha CLAUDE-TEST', accountNumber: '50100234567891', ifsc: 'HDFC0001234' });
    expect(saved.body).toMatchObject({ accountMasked: 'XXXXXXXX7891', validation: 'VALID' });
    expect(JSON.stringify(server.db.integration.beneficiaries)).not.toContain('50100234567891');
    expect((await mockCall(server, 'ops', 'POST', `/api/v1/payouts/${held.id}/retry`)).body).toMatchObject({ status: 'SENT', beneficiaryAccountMasked: 'XXXXXXXX7891' });
  });
});

describe('collections', () => {
  it('creates a payment link, posts the receipt when it is paid, and reconciles both ways', async () => {
    const server = createMockServer();
    const loan = server.db.loans[0];
    const order = await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/collection-orders`, { amount: '1500.00', methods: ['UPI'] });
    expect(order.status).toBe(201);
    expect(order.body.paymentUrl).toMatch(/^https:\/\//);
    const events = loan.events.length;
    await mockCall(server, 'admin', 'POST', '/api/v1/integrations/simulator/callbacks', { kind: 'collection', reference: order.body.reference, status: 'PAID' });
    expect(loan.events.length).toBe(events + 1);
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/collection-orders/${order.body.id}`)).body.status).toBe('PAID');
    const recon = (await mockCall(server, 'maker', 'GET', '/api/v1/gateway-payments/reconciliation')).body as Array<{ category: string; paymentId: string; providerPaymentId: string }>;
    expect(recon.map((r) => r.category).sort()).toEqual(['AMOUNT_MISMATCH', 'PAYMENT_NOT_POSTED', 'POSTED_NOT_SETTLED', 'SETTLED_NOT_RECEIVED']);
    const unmatched = recon.find((r) => r.category === 'PAYMENT_NOT_POSTED')!;
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/gateway-payments/${unmatched.paymentId}/resolve`, { refund: true, note: 'x' })).status).toBe(403);
    expect((await mockCall(server, 'ops', 'POST', `/api/v1/gateway-payments/${unmatched.paymentId}/resolve`, { refund: true })).status).toBe(422);
    expect((await mockCall(server, 'ops', 'POST', `/api/v1/gateway-payments/${unmatched.paymentId}/resolve`, { refund: true, note: 'Payer unknown CLAUDE-TEST' })).body.status).toBe('REFUND_DUE');
    const csv = 'provider_payment_id,amount,settled_on,fee,utr\nSIMPAY-CLAUDE-TEST-002,2500.00,2026-06-29,5.90,SETTL1\nSIMPAY-CLAUDE-TEST-001,9792.00,2026-06-29,,\n';
    const up = await sendText(server, 'ops', '/api/v1/gateway-settlements/upload?fileRef=settle-1', csv, 'text/csv');
    expect(up.body).toEqual({ fileRef: 'settle-1', rows: 2, added: 1, alreadyLoaded: 1 });
  });
});

describe('mandates and NACH', () => {
  it('registers a mandate with the account masked and moves it through its statuses', async () => {
    const server = createMockServer();
    const loan = server.db.loans[3];
    const input = { holderName: 'Esha CLAUDE-TEST', accountNumber: '60200345678912', ifsc: 'HDFC0001234', maxAmount: '10000.00', startDate: '2026-07-01' };
    const m = await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/mandates`, input);
    expect(m.status).toBe(201);
    expect(m.body).toMatchObject({ status: 'SUBMITTED', debitAccountMasked: 'XXXXXXXX8912' });
    expect(JSON.stringify(server.db.integration.mandates)).not.toContain('60200345678912');
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/loans/${loan.id}/mandates`, input)).status).toBe(409);
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/mandates/${m.body.id}/status`, { status: 'ACTIVE' })).status).toBe(403);
    expect((await mockCall(server, 'ops', 'POST', `/api/v1/mandates/${m.body.id}/status`, { status: 'ACTIVE' })).status).toBe(422);
    const active = await mockCall(server, 'ops', 'POST', `/api/v1/mandates/${m.body.id}/status`, { status: 'ACTIVE', umrn: 'HDFC7000000000001234' });
    expect(active.body.events.map((e: { to: string }) => e.to)).toEqual(['DRAFT', 'SUBMITTED', 'ACTIVE']);
    expect((await mockCall(server, 'ops', 'POST', `/api/v1/mandates/${m.body.id}/status`, { status: 'REJECTED' })).status).toBe(409);
  });

  it('generates a GENERIC presentation file and processes the response row by row, once', async () => {
    const server = createMockServer();
    const gen = await mockCall(server, 'ops', 'POST', '/api/v1/nach/presentations/generate');
    expect(gen.body).toHaveLength(1);
    const file = gen.body[0];
    expect(file).toMatchObject({ direction: 'PRESENTATION', format: 'GENERIC', recordCount: 2, status: 'GENERATED' });
    expect((await mockCall(server, 'ops', 'POST', '/api/v1/nach/presentations/generate')).body).toEqual([]);
    const content = await server.fetch(new Request(`http://localhost/api/v1/nach/files/${file.id}/content`, { headers: { Authorization: `Bearer ${mockToken('ops')}` } }));
    expect(content.headers.get('Content-Type')).toBe('text/plain');
    const text = await content.text();
    expect(text.split('\r\n').filter((l) => l.startsWith('D,'))).toHaveLength(2);
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/nach/files/${file.id}/content`)).status).toBe(403);

    const sim = await mockCall(server, 'admin', 'POST', `/api/v1/nach/files/${file.id}/simulate-response`);
    expect(sim.body).toMatchObject({ direction: 'RESPONSE', status: 'PROCESSED', summary: { success: 1, bounced: 1, alreadyRecorded: 0, errors: [] } });
    const bounced = sim.body.summary.rows.find((r: { status: string }) => r.status === 'BOUNCED');
    expect(bounced).toMatchObject({ returnCode: '04', returnReason: 'Balance insufficient' });
    expect(bounced.representOn).toBeTruthy();

    const response = server.db.integration.nachFiles.find((f) => f.direction === 'RESPONSE')!.content;
    expect((await sendText(server, 'ops', '/api/v1/nach/responses', response)).status).toBe(409);
    // same outcome, different bytes: refused on its control totals
    expect((await sendText(server, 'ops', '/api/v1/nach/responses', response + '\r\n')).body).toMatchObject({ status: 'DUPLICATE' });
    const tampered = response.replace(/^T,(.*),2,/m, 'T,$1,3,');
    expect((await sendText(server, 'ops', '/api/v1/nach/responses', tampered)).body).toMatchObject({ status: 'REJECTED', error: expect.stringContaining('control totals') });
  });
});

describe('webhooks and API clients', () => {
  it('accepts only https endpoints; the signing secret is collected once, by the proposer', async () => {
    const server = createMockServer();
    for (const url of ['http://los.partner.example/hook', 'https://10.0.0.5/hook', 'https://localhost/hook', 'https://los.partner.example:8080/hook']) {
      expect((await mockCall(server, 'admin', 'POST', '/api/v1/webhooks/endpoints', { name: 'X CLAUDE-TEST', url, eventTypes: ['loan.closed'] })).status).toBe(422);
    }
    const p = await mockCall(server, 'admin', 'POST', '/api/v1/webhooks/endpoints', { name: 'Ledger CLAUDE-TEST', url: 'https://ledger.partner.example/hook', eventTypes: ['loan.closed', 'payout.status'] });
    expect(p.status).toBe(202);
    await approve(server, 'checker', p.body.id);
    const id = p.body.entityId;
    maker.permissions = [...makerPerms, 'webhook:admin'];
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/webhooks/endpoints/${id}/secret`)).status).toBe(403);
    const secret = await mockCall(server, 'admin', 'POST', `/api/v1/webhooks/endpoints/${id}/secret`);
    expect(secret.body).toMatchObject({ keyId: 'k1', secret: expect.stringMatching(/^whsec_/) });
    expect((await mockCall(server, 'admin', 'POST', `/api/v1/webhooks/endpoints/${id}/secret`)).status).toBe(409);
    expect(JSON.stringify(server.db.integration.endpoints)).not.toContain(secret.body.secret);
    const listed = (await mockCall(server, 'auditor', 'GET', '/api/v1/webhooks/endpoints')).body.find((e: { id: string }) => e.id === id);
    expect(listed).toMatchObject({ secretPending: false, keysInForce: ['k1'] });
    expect(listed).not.toHaveProperty('pendingFor');
  });

  it('replays a dead delivery as a new delivery', async () => {
    const server = createMockServer();
    const dead = server.db.integration.deliveries.find((d) => d.status === 'DEAD')!;
    expect((await mockCall(server, 'auditor', 'POST', `/api/v1/webhooks/deliveries/${dead.id}/replay`)).status).toBe(403);
    const again = await mockCall(server, 'admin', 'POST', `/api/v1/webhooks/deliveries/${dead.id}/replay`);
    expect(again.body).toMatchObject({ replayOf: dead.id, requestedBy: 'admin', eventId: dead.eventId });
    expect((await mockCall(server, 'admin', 'POST', `/api/v1/webhooks/endpoints/${dead.endpointId}/replay-dead`)).body.replayed).toBe(0);
  });

  it('an API client with a money-moving scope needs two different checkers', async () => {
    const server = createMockServer();
    maker.permissions = [...makerPerms, 'apiclient:admin'];
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/api-clients', { clientId: 'x-claude-test', name: 'X', scopes: ['approval:approve'], homeBranch: 'HO' })).status).toBe(422);
    const plain = await mockCall(server, 'maker', 'POST', '/api/v1/api-clients', { clientId: 'viewer-claude-test', name: 'Viewer CLAUDE-TEST', scopes: ['loan:view'], homeBranch: 'HO' });
    expect(plain.body).toMatchObject({ checkersRequired: 1, action: 'CREATE' });
    const p = await mockCall(server, 'maker', 'POST', '/api/v1/api-clients', { clientId: 'los2-claude-test', name: 'LOS 2 CLAUDE-TEST', scopes: ['loan:create', 'loan:stp'], homeBranch: 'HO' });
    expect(p.body).toMatchObject({ checkersRequired: 2, action: 'CREATE_SENSITIVE', entityId: 'ext-los2-claude-test' });
    expect((await approve(server, 'checker', p.body.id)).body).toMatchObject({ status: 'PENDING', approvalsSoFar: 1 });
    expect((await mockCall(server, 'auditor', 'GET', '/api/v1/api-clients/ext-los2-claude-test')).status).toBe(404);
    expect((await approve(server, 'checker', p.body.id)).status).toBe(409);
    expect((await approve(server, 'admin', p.body.id)).body).toMatchObject({ status: 'APPROVED' });
    expect((await mockCall(server, 'admin', 'POST', '/api/v1/api-clients/ext-los2-claude-test/secret')).status).toBe(403);
    const secret = await mockCall(server, 'maker', 'POST', '/api/v1/api-clients/ext-los2-claude-test/secret');
    expect(secret.body).toMatchObject({ clientId: 'ext-los2-claude-test', grantType: 'client_credentials' });
    expect(secret.body.clientSecret.length).toBeGreaterThan(20);
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/api-clients/ext-los2-claude-test/secret')).status).toBe(409);
    // adding a money-moving scope later needs two checkers again; removing one does not
    const add = await mockCall(server, 'maker', 'PUT', '/api/v1/api-clients/ext-viewer-claude-test/scopes', { scopes: ['loan:view'] });
    expect(add.status).toBe(404);
    const less = await mockCall(server, 'maker', 'PUT', '/api/v1/api-clients/ext-los2-claude-test/scopes', { scopes: ['loan:create'] });
    expect(less.body.checkersRequired).toBe(1);
  });
});

describe('messages', () => {
  it('refuses a placeholder the event does not offer and an SMS without its DLT registration', async () => {
    const server = createMockServer();
    const base = { code: 'PAYMENT_RECEIVED', channel: 'SMS', body: 'Dear {{name}}, received Rs {{amount}}.', dltEntityId: '1101', dltTemplateId: '1107', dltHeader: 'DEMONB' };
    expect(placeholders('{{name}} {{ amount }} {{name}}')).toEqual(['name', 'amount']);
    expect(dltForm(base.body)).toBe('Dear {#var#}, received Rs {#var#}.');
    const unknown = await mockCall(server, 'admin', 'POST', '/api/v1/message-templates', { ...base, body: 'Pay by {{due_date}}' });
    expect(unknown.body.detail).toContain("placeholder 'due_date' is not available for PAYMENT_RECEIVED");
    expect((await mockCall(server, 'admin', 'POST', '/api/v1/message-templates', { ...base, dltTemplateId: '' })).status).toBe(422);
    expect((await mockCall(server, 'maker', 'POST', '/api/v1/message-templates', base)).status).toBe(403);
    const p = await mockCall(server, 'admin', 'POST', '/api/v1/message-templates', base);
    expect(p.body).toMatchObject({ entityType: 'MESSAGE_TEMPLATE', action: 'UPDATE' });
    await approve(server, 'checker', p.body.id);
    const t = (await mockCall(server, 'maker', 'GET', '/api/v1/message-templates')).body.find((x: { code: string; channel: string }) => x.code === 'PAYMENT_RECEIVED' && x.channel === 'SMS');
    expect(t).toMatchObject({ version: 2, body: base.body, dltForm: 'Dear {#var#}, received Rs {#var#}.' });
    const log = (await mockCall(server, 'auditor', 'GET', '/api/v1/messages')).body as Array<Record<string, unknown>>;
    expect(log.every((m) => /^[Xx*a-z]/.test(String(m.recipientMasked)) && !('body' in m))).toBe(true);
    expect(JSON.stringify(log)).not.toMatch(/9000000\d{3}/);
  });
});
