import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { previewTemplate } from '../pages/integrations/MessagesPage';
import { webhookUrlError } from '../pages/integrations/WebhooksPage';
import { mockCall, renderApp } from './utils';

const saved: Array<{ blob: Blob; name: string }> = [];
beforeEach(() => {
  saved.length = 0;
  let last: Blob | null = null;
  URL.createObjectURL = vi.fn((b: Blob) => ((last = b), 'blob:mock'));
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    if (this.download && last) saved.push({ blob: last, name: this.download });
  });
});
afterEach(() => vi.restoreAllMocks());

const rowOf = (text: string | RegExp) => screen.getAllByText(text)[0].closest('tr')!;
const approve = (server: MockServer, id: string, user = 'checker') => mockCall(server, user, 'POST', `/api/v1/approvals/${id}/approve`, {});
const pendingOf = (server: MockServer, entityType: string) => server.db.approvals.find((a) => a.approval.entityType === entityType && a.approval.status === 'PENDING')!;
/** Everything the page shows, including values of form fields. */
const visibleText = () => document.body.textContent + [...document.querySelectorAll('input,textarea')].map((e) => (e as HTMLInputElement).value).join('|');

describe('providers', () => {
  it('shows secrets only as set + last four, and hides changes from users without integration:admin', async () => {
    renderApp({ user: 'maker', route: '/integrations/providers' });
    const secret = await screen.findByTestId('secret-PAYOUT-webhookSecret');
    expect(secret).toHaveTextContent('webhookSecret: set ••••k3y9');
    expect(within(rowOf('E-mail')).getByText('Not configured')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Change|Configure|Deactivate/ })).not.toBeInTheDocument();
  });

  it('types a secret into a password field, sends it once and clears it; the approval never carries it', async () => {
    const user = userEvent.setup();
    const SECRET = 'sk-CLAUDE-TEST-typed-secret-77aa';
    const { server } = renderApp({ user: 'admin', route: '/integrations/providers' });
    await user.click(await screen.findByRole('button', { name: 'Change SMS provider' }));
    const d = await screen.findByRole('dialog', { name: 'SMS provider' });
    await user.selectOptions(within(d).getByLabelText(/^Provider/), 'GENERIC_HTTP');
    expect(within(d).getByText(/Not verified against the provider/)).toBeInTheDocument();
    const key = within(d).getByLabelText(/apiKey/);
    expect(key).toHaveAttribute('type', 'password');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('Required')).toBeInTheDocument();
    await user.type(within(d).getByLabelText('url'), 'https://sms.partner.example/send');
    await user.type(key, SECRET);
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Provider configuration sent for approval.')).toBeInTheDocument();
    expect(visibleText()).not.toContain(SECRET);
    const a = pendingOf(server, 'PROVIDER_CONFIG');
    expect(JSON.stringify(a)).not.toContain(SECRET);
    expect(a.approval.proposed).toMatchObject({ provider: 'GENERIC_HTTP', secretHints: { apiKey: '77aa' } });
  });
});

describe('payouts', () => {
  it('shows status, UTR and the masked beneficiary; a failed payout shows what became of the disbursement', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'auditor', route: '/integrations/payouts' });
    expect(await screen.findByText('HDFCN26015000123')).toBeInTheDocument();
    const failed = rowOf('Beneficiary account is closed');
    expect(within(failed).getByText('Parked: disbursement not reversed')).toBeInTheDocument();
    expect(within(failed).getByLabelText(/account ending 9012/)).toHaveTextContent('XXXXXXXX9012');
    expect(screen.queryByRole('button', { name: /Set beneficiary|Resume|New attempt/ })).not.toBeInTheDocument();
    await user.click(within(failed).getByRole('button', { name: /Open payout/ }));
    const d = await screen.findByRole('dialog', { name: /^Payout PO-/ });
    expect(await within(d).findByRole('list', { name: 'Status history' })).toHaveTextContent('Sent → Failed');
  });

  it('operations records a beneficiary (number never left on screen) and resumes the payout on hold', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'ops', route: '/integrations/payouts' });
    const held = server.db.integration.payouts.find((p) => p.status === 'ON_HOLD')!;
    await user.click(await screen.findByRole('button', { name: `Set beneficiary for ${held.loanNo}` }));
    const d = await screen.findByRole('dialog', { name: `Payout beneficiary for ${held.loanNo}` });
    await user.type(within(d).getByLabelText(/Account holder/), 'Esha CLAUDE-TEST');
    await user.type(within(d).getByLabelText(/IFSC/), 'hdfc0001234');
    expect(within(d).getByLabelText(/^Account number/)).toHaveAttribute('type', 'password');
    await user.type(within(d).getByLabelText(/^Account number/), '50100234567891');
    await user.type(within(d).getByLabelText(/Confirm account number/), '50100234567890');
    await user.click(within(d).getByRole('button', { name: 'Validate and save' }));
    expect(within(d).getByText('The two account numbers differ')).toBeInTheDocument();
    await user.clear(within(d).getByLabelText(/Confirm account number/));
    await user.type(within(d).getByLabelText(/Confirm account number/), '50100234567891');
    await user.click(within(d).getByRole('button', { name: 'Validate and save' }));
    expect(await screen.findByText(/Beneficiary XXXXXXXX7891 recorded/)).toBeInTheDocument();
    expect(visibleText()).not.toContain('50100234567891');
    await user.click(await screen.findByRole('button', { name: `Resume payout ${held.reference}` }));
    expect(await screen.findByText(`Payout ${held.reference} is sent.`)).toBeInTheDocument();
  });

  it('after a simulated failure the reversal request is linked from the payout', async () => {
    const server = createMockServer();
    const sent = server.db.integration.payouts.find((p) => p.status === 'SENT')!;
    await mockCall(server, 'admin', 'POST', '/api/v1/integrations/simulator/callbacks', { kind: 'payout', reference: sent.reference, status: 'FAILED', reason: 'Account frozen' });
    renderApp({ user: 'checker', route: '/integrations/payouts', server });
    const row = (await screen.findByText('Account frozen')).closest('tr')!;
    expect(within(row).getByText('Reversal awaiting approval')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'View request' })).toHaveAttribute('href', `/approvals?id=${sent.reversalApprovalId}`);
  });
});

describe('collections', () => {
  it('creates a payment link for a loan and lists it', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/integrations/collections' });
    const loan = server.db.loans[1];
    await screen.findByRole('option', { name: new RegExp(loan.loanNo), hidden: true });
    await user.selectOptions(screen.getByLabelText(/^Loan/), loan.id);
    expect(await screen.findByText('No payment links for this loan')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Create payment link' }));
    expect(screen.getByText('Enter a positive amount')).toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Amount/), '6947.50');
    await user.click(screen.getByRole('button', { name: 'Create payment link' }));
    expect(await screen.findByText(`Payment link CO-${loan.loanNo}-01 created.`)).toBeInTheDocument();
    const row = await waitFor(() => rowOf(`CO-${loan.loanNo}-01`));
    expect(within(row).getByText('₹6,947.50')).toBeInTheDocument();
    expect(within(row).getByText(`https://pay.simulator.example/l/CO-${loan.loanNo}-01`)).toBeInTheDocument();
  });

  it('reconciliation shows what is unmatched in both directions, and an unmatched receipt is resolved with a note', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'ops', route: '/integrations/collections' });
    await user.click(await screen.findByRole('tab', { name: 'Reconciliation' }));
    expect(await screen.findByText(/3 unmatched/)).toHaveTextContent("1 received by us and not fully through (not posted, not settled, or to refund), 2 in the gateway's settlement");
    const table = screen.getByRole('table', { name: 'Gateway reconciliation' });
    for (const finding of ['Received, not posted', 'Settled, not received', 'Amount mismatch']) expect(within(table).getByText(finding)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Resolve SIMPAY-CLAUDE-TEST-002' }));
    const d = await screen.findByRole('dialog', { name: 'Resolve SIMPAY-CLAUDE-TEST-002' });
    await user.selectOptions(within(d).getByLabelText('What to do'), 'refund');
    await user.click(within(d).getByRole('button', { name: 'Mark for refund' }));
    expect(within(d).getByText('Required')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/Note/), 'Payer not identified CLAUDE-TEST');
    await user.click(within(d).getByRole('button', { name: 'Mark for refund' }));
    expect(await screen.findByText('Payment marked for refund.')).toBeInTheDocument();
    expect(await within(table).findByText('Refund due')).toBeInTheDocument();
    expect(server.db.integration.payments.find((p) => p.providerPaymentId === 'SIMPAY-CLAUDE-TEST-002')).toMatchObject({ status: 'REFUND_DUE', resolutionNote: 'Payer not identified CLAUDE-TEST' });
  });

  it('a viewer sees neither the create form nor Resolve', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'checker', route: '/integrations/collections' });
    await screen.findByRole('option', { name: new RegExp(server.db.loans[0].loanNo), hidden: true });
    await user.selectOptions(screen.getByLabelText(/^Loan/), server.db.loans[0].id);
    expect(await screen.findByText(`CO-${server.db.loans[0].loanNo}-01`)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create payment link' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Reconciliation' }));
    await within(await screen.findByRole('table', { name: 'Gateway reconciliation' })).findByText('Received, not posted');
    expect(screen.queryByRole('button', { name: /Resolve/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Settlement file/)).not.toBeInTheDocument();
  });
});

describe('mandates', () => {
  it('registers a mandate; the account is typed into a password field and shown masked afterwards', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/integrations/mandates' });
    const loan = server.db.loans[3];
    expect(await screen.findByText('Signature mismatch')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Register mandate' }));
    const d = await screen.findByRole('dialog', { name: 'Register mandate' });
    await within(d).findByRole('option', { name: new RegExp(loan.loanNo) });
    await user.selectOptions(within(d).getByLabelText(/^Loan/), loan.id);
    await user.type(within(d).getByLabelText(/Account holder/), 'Esha CLAUDE-TEST');
    expect(within(d).getByLabelText(/^Account number/)).toHaveAttribute('type', 'password');
    await user.type(within(d).getByLabelText(/^Account number/), '60200345678912');
    await user.type(within(d).getByLabelText(/Confirm account number/), '60200345678912');
    await user.type(within(d).getByLabelText(/IFSC/), 'HDFC0001234');
    await user.click(within(d).getByRole('button', { name: 'Register mandate' }));
    expect(within(d).getByText('Enter a positive amount')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/Maximum amount per debit/), '10000');
    await user.click(within(d).getByRole('button', { name: 'Register mandate' }));
    expect(await screen.findByText(`Mandate MD-${loan.loanNo}-02 registered on account XXXXXXXX8912; it is submitted.`)).toBeInTheDocument();
    expect(visibleText()).not.toContain('60200345678912');
    expect(await screen.findByText(`MD-${loan.loanNo}-02`)).toBeInTheDocument();
  });

  it('opens a mandate with its status timeline; only mandate:admin can set a status by hand', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const submitted = server.db.integration.mandates.find((m) => m.status === 'SUBMITTED')!;
    const { unmount } = renderApp({ user: 'maker', route: '/integrations/mandates', server });
    await user.click(await screen.findByRole('row', { name: `Open mandate ${submitted.mandateRef}` }));
    let d = await screen.findByRole('dialog', { name: `Mandate ${submitted.mandateRef}` });
    expect(await within(d).findByRole('list', { name: 'Status history' })).toHaveTextContent('Draft → Submitted');
    expect(within(d).queryByText('Set status by hand')).not.toBeInTheDocument();
    unmount();

    renderApp({ user: 'ops', route: '/integrations/mandates', server });
    await user.click(await screen.findByRole('row', { name: `Open mandate ${submitted.mandateRef}` }));
    d = await screen.findByRole('dialog', { name: `Mandate ${submitted.mandateRef}` });
    await user.selectOptions(await within(d).findByLabelText('New status'), 'ACTIVE');
    await user.click(within(d).getByRole('button', { name: 'Set status' }));
    expect(within(d).getByText('20 letters or digits')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/UMRN/), 'SBIN7000000000004567');
    await user.click(within(d).getByRole('button', { name: 'Set status' }));
    expect(await screen.findByText(`Mandate ${submitted.mandateRef} is now active.`)).toBeInTheDocument();
    await waitFor(() => expect(within(d).getByRole('list', { name: 'Status history' })).toHaveTextContent('Submitted → Active'));
  });
});

describe('NACH files', () => {
  it('says the layout is the built-in GENERIC one, generates a file and downloads it as a blob', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'ops', route: '/integrations/nach' });
    expect(await screen.findByText('These files use the built-in GENERIC layout.')).toBeInTheDocument();
    expect(screen.getByText(/no bank will accept it/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Generate presentation file' }));
    expect(await screen.findByText(/Presentation file NP20260701-01 generated with 2 debit\(s\)\./)).toBeInTheDocument();
    const row = await waitFor(() => rowOf('NP20260701-01'));
    expect(within(row).getByText('GENERIC')).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /Simulate/ })).not.toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: 'Download NP20260701-01' }));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].name).toBe('NP20260701-01.csv');
    expect(await saved[0].blob.text()).toContain('H,NP20260701-01,');
  });

  it('shows per-row results with the bounce reason, and refuses the same response twice', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const [file] = (await mockCall(server, 'ops', 'POST', '/api/v1/nach/presentations/generate')).body;
    renderApp({ user: 'admin', route: '/integrations/nach', server });
    await user.click(await screen.findByRole('button', { name: `Simulate the bank's response to ${file.fileRef} (test only)` }));
    const result = await screen.findByTestId('nach-result');
    expect(within(result).getByText(/1 debited, 1 bounced, 0 already recorded/)).toBeInTheDocument();
    const rows = within(result).getByRole('table', { name: 'Result per row' });
    const bounced = within(rows).getByText('Bounced').closest('tr')!;
    expect(bounced).toHaveTextContent('04 Balance insufficient');
    expect(bounced).toHaveTextContent('Presented again on');
    expect(within(rows).getByText('Repayment posted')).toBeInTheDocument();

    const response = server.db.integration.nachFiles.find((f) => f.direction === 'RESPONSE')!.content;
    await user.upload(screen.getByLabelText('Response file'), new File([response], 'response.csv', { type: 'text/plain' }));
    expect(await screen.findByText(/this response file was already received/)).toBeInTheDocument();
    await user.upload(screen.getByLabelText('Response file'), new File([response.replace(/^T,(.*),2,/m, 'T,$1,5,')], 'bad.csv', { type: 'text/plain' }));
    expect(await screen.findByText(/File refused: the control totals in the trailer do not match/)).toBeInTheDocument();
  });
});

describe('webhooks', () => {
  it('validates the URL as https with a public name', () => {
    expect(webhookUrlError('https://los.partner.example/hooks')).toBeNull();
    expect(webhookUrlError('https://los.partner.example:8443/hooks')).toBeNull();
    expect(webhookUrlError('http://los.partner.example/hooks')).toBe('Must be https');
    expect(webhookUrlError('https://192.168.1.5/hooks')).toMatch(/public DNS name/);
    expect(webhookUrlError('https://localhost/hooks')).toMatch(/public DNS name/);
    expect(webhookUrlError('https://los.partner.example:9000/x')).toBe('Port must be 443 or 8443');
    expect(webhookUrlError('los.partner.example')).toMatch(/full URL/);
  });

  it('proposes an endpoint; after approval the proposer collects the signing secret exactly once', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    const { server, unmount } = renderApp({ user: 'admin', route: '/integrations/webhooks' });
    await user.click(await screen.findByRole('button', { name: 'New endpoint' }));
    let d = await screen.findByRole('dialog', { name: 'New webhook endpoint' });
    await user.type(within(d).getByLabelText(/^Name/), 'Ledger CLAUDE-TEST');
    await user.type(within(d).getByLabelText(/^URL/), 'http://ledger.partner.example/hook');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('Must be https')).toBeInTheDocument();
    expect(within(d).getByText('Choose at least one event')).toBeInTheDocument();
    await user.clear(within(d).getByLabelText(/^URL/));
    await user.type(within(d).getByLabelText(/^URL/), 'https://ledger.partner.example/hook');
    await user.click(await within(d).findByLabelText('loan.closed'));
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('New endpoint sent for approval.')).toBeInTheDocument();
    await approve(server, pendingOf(server, 'WEBHOOK_ENDPOINT').approval.id);
    unmount();

    renderApp({ user: 'admin', route: '/integrations/webhooks', server });
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const row = await waitFor(() => rowOf('Ledger CLAUDE-TEST'));
    expect(within(row).getByText('Secret waiting to be collected')).toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: 'Collect signing secret of Ledger CLAUDE-TEST' }));
    d = await screen.findByRole('dialog', { name: 'Signing secret' });
    expect(within(d).getByText(/only time the signing secret is shown/)).toBeInTheDocument();
    const secret = (within(d).getByLabelText('Signing secret') as HTMLInputElement).value;
    expect(secret).toMatch(/^whsec_.{20,}/);
    const done = within(d).getByRole('button', { name: 'Done' });
    expect(done).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Signing secret' })).toBeInTheDocument();
    await user.click(within(d).getByRole('button', { name: 'Copy signing secret' }));
    expect(writeText).toHaveBeenCalledWith(secret);
    expect(await within(d).findByText('Signing secret copied to the clipboard.')).toBeInTheDocument();
    await user.click(within(d).getByLabelText(/I have stored it in a safe place/));
    await user.click(done);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(visibleText()).not.toContain(secret);
    expect(JSON.stringify([localStorage, sessionStorage])).not.toContain(secret);
    await waitFor(() => expect(within(rowOf('Ledger CLAUDE-TEST')).queryByRole('button', { name: /Collect signing secret/ })).not.toBeInTheDocument());
    expect(within(rowOf('Ledger CLAUDE-TEST')).getByText('k1')).toBeInTheDocument();
  });

  it('lists deliveries with their attempts and replays a dead one; viewers get no actions', async () => {
    const user = userEvent.setup();
    const { unmount, server } = renderApp({ user: 'admin', route: '/integrations/webhooks' });
    await user.click(await screen.findByRole('tab', { name: 'Deliveries' }));
    const dead = (await within(await screen.findByRole('table', { name: 'Webhook deliveries' })).findByText('payment.bounced')).closest('tr')!;
    expect(within(dead).getByText('Dead')).toBeInTheDocument();
    expect(dead).toHaveTextContent('6');
    await user.click(within(dead).getByRole('button', { name: /^Attempts of/ }));
    const d = await screen.findByRole('dialog', { name: 'Delivery of payment.bounced' });
    expect(await within(d).findByText('timed out')).toBeInTheDocument();
    expect(within(d).getAllByRole('row')).toHaveLength(7);
    await user.click(within(d).getAllByRole('button', { name: 'Close' }).at(-1)!);
    await user.click(within(dead).getByRole('button', { name: /^Replay payment\.bounced/ }));
    expect(await screen.findByText(/Sent again as a new delivery/)).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Webhook deliveries' })).getAllByRole('row')).toHaveLength(5));
    expect(server.db.integration.deliveries.filter((x) => x.replayOf)).toHaveLength(1);
    unmount();

    renderApp({ user: 'auditor', route: '/integrations/webhooks', server });
    await within(await screen.findByRole('table', { name: 'Webhook endpoints' })).findByText('LOS CLAUDE-TEST');
    expect(screen.queryByRole('button', { name: /New endpoint|Rotate secret|Disable|Change/ })).not.toBeInTheDocument();
  });
});

describe('API clients', () => {
  it('warns that money-moving scopes need two checkers and proposes the client', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'admin', route: '/integrations/api-clients' });
    expect(await screen.findByText(/needs two different checkers/)).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'New API client' }));
    const d = await screen.findByRole('dialog', { name: 'New API client' });
    await user.type(within(d).getByLabelText(/Client id/), 'los2-claude-test');
    expect(within(d).getByText('Stored as ext-los2-claude-test')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/^Name/), 'LOS 2 CLAUDE-TEST');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('Choose at least one scope')).toBeInTheDocument();
    await user.click(await within(d).findByLabelText(/^loan:create/));
    expect(within(d).queryByText(/Two different checkers must approve/)).not.toBeInTheDocument();
    await user.click(within(d).getByLabelText(/^loan:stp/));
    expect(within(d).getByText('Two different checkers must approve this: it grants loan:stp, which can move money.')).toBeInTheDocument();
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('New API client sent for approval.')).toBeInTheDocument();
    expect(pendingOf(server, 'API_CLIENT').approval).toMatchObject({ checkersRequired: 2, entityId: 'ext-los2-claude-test' });
  });

  it('collects a rotated client secret once and shows nothing of it afterwards', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const rotation = await mockCall(server, 'admin', 'POST', '/api/v1/api-clients/ext-los-claude-test/actions', { action: 'ROTATE_SECRET' });
    await approve(server, rotation.body.id);
    renderApp({ user: 'admin', route: '/integrations/api-clients', server });
    const row = await waitFor(() => rowOf('ext-los-claude-test'));
    expect(within(row).getByText('Waiting to be collected')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Rotate secret of ext-los-claude-test' })).toBeDisabled();
    await user.click(within(row).getByRole('button', { name: 'Collect secret of ext-los-claude-test' }));
    const d = await screen.findByRole('dialog', { name: 'Client secret' });
    const secret = (within(d).getByLabelText('Client secret') as HTMLInputElement).value;
    expect(secret.length).toBeGreaterThan(20);
    expect(within(d).getByLabelText('Grant type')).toHaveValue('client_credentials');
    await user.click(within(d).getByLabelText(/I have stored it in a safe place/));
    await user.click(within(d).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(visibleText()).not.toContain(secret);
    expect((await mockCall(server, 'admin', 'POST', '/api/v1/api-clients/ext-los-claude-test/secret')).status).toBe(409);
  });

  it('a user who did not propose it is told why the secret cannot be collected', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    server.db.integration.clients[0].pendingFor = 'someone-else';
    server.db.integration.clients[0].secretPending = true;
    renderApp({ user: 'admin', route: '/integrations/api-clients', server });
    await user.click(await screen.findByRole('button', { name: 'Collect secret of ext-los-claude-test' }));
    expect(await screen.findByText(/the secret can be collected only by the user who proposed it/)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('messages', () => {
  it('previews with sample values and fails on unknown or unclosed placeholders', () => {
    const allowed = ['name', 'amount', 'loan_no', 'date'];
    expect(previewTemplate('Dear {{name}}, Rs {{ amount }} on {{loan_no}}', allowed)).toEqual({ text: 'Dear Anu, Rs 9792.00 on 100100000012', unknown: [], malformed: false });
    expect(previewTemplate('Pay by {{due_date}} {{nmae}}', allowed).unknown).toEqual(['due_date', 'nmae']);
    expect(previewTemplate('Dear {{name', allowed).malformed).toBe(true);
  });

  it('the template dialog shows a live preview that fails on an unknown placeholder, then proposes the template', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'admin', route: '/integrations/messages' });
    await user.click(await screen.findByRole('button', { name: 'New template' }));
    const d = await screen.findByRole('dialog', { name: 'New message template' });
    await user.selectOptions(within(d).getByLabelText('Event'), 'DUE_REMINDER');
    await waitFor(() => expect(within(d).getByTestId('placeholders')).toHaveTextContent('{{due_date}}'));
    const text = within(d).getByLabelText(/^Text/);
    await user.click(text);
    await user.paste('Dear {{name}}, Rs {{amount}} is due on {{due_date}} for {{reason}}.');
    expect(within(d).getByText('Not available for this event: {{reason}}')).toBeInTheDocument();
    expect(within(d).getByText('The preview cannot be made: fix the placeholders first.')).toBeInTheDocument();
    expect(within(d).queryByTestId('template-preview')).not.toBeInTheDocument();
    await user.clear(text);
    await user.click(text);
    await user.paste('Dear {{name}}, Rs {{amount}} is due on {{due_date}} on loan {{loan_no}}.');
    expect(within(d).getByTestId('template-preview')).toHaveTextContent('Dear Anu, Rs 9792.00 is due on 15-Jul-2026 on loan 100100000012.');
    expect(within(d).getByText(/As registered on DLT/)).toHaveTextContent('Dear {#var#}, Rs {#var#} is due on {#var#} on loan {#var#}.');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getAllByText('Digits, as registered on the DLT platform')).toHaveLength(2);
    await user.type(within(d).getByLabelText(/DLT entity id/), '1101234567890123456');
    await user.type(within(d).getByLabelText(/DLT template id/), '1107160000000000009');
    await user.type(within(d).getByLabelText(/DLT header/), 'DEMONB');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Message template sent for approval.')).toBeInTheDocument();
    expect(pendingOf(server, 'MESSAGE_TEMPLATE').approval).toMatchObject({ action: 'CREATE', entityId: 'DUE_REMINDER/SMS/en' });
  });

  it('the delivery log shows masked recipients only, and viewers cannot change templates', async () => {
    const user = userEvent.setup();
    renderApp({ user: 'auditor', route: '/integrations/messages' });
    expect(await screen.findByText(/Dear \{\{name\}\}, Rs \{\{net_amount\}\} has been disbursed/)).toBeInTheDocument();
    expect(screen.getAllByText(/Entity 1101234567890123456/).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /New template|Change/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Delivery log' }));
    expect(await screen.findByText('XXXXXX0002')).toBeInTheDocument();
    expect(screen.getByText('Opted out')).toBeInTheDocument();
    expect(screen.getByText('no EMAIL provider is active')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/9000000\d{3}/);
  });
});

describe('simulator', () => {
  it('is hidden without integration:simulate', async () => {
    renderApp({ user: 'maker', route: '/integrations/simulator' });
    expect(await screen.findByRole('heading', { name: /403/ })).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).queryByText(/Simulator/)).not.toBeInTheDocument();
  });

  it('is clearly labelled test-only and plays a payout callback', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'admin', route: '/integrations/simulator' });
    const sent = server.db.integration.payouts.find((p) => p.status === 'SENT')!;
    expect(within(await screen.findByRole('navigation', { name: 'Main' })).getByText('Simulator (test only)')).toBeInTheDocument();
    expect(within(screen.getByTestId('test-only')).getByText('Test only.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Send simulated callback' }));
    expect(screen.getByText('Required')).toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Reference/), sent.reference!);
    await user.type(screen.getByLabelText(/Event id/), 'evt-ui-1');
    await user.click(screen.getByRole('button', { name: 'Send simulated callback' }));
    expect(await screen.findByText(/Callback accepted and processed\./)).toBeInTheDocument();
    expect(sent).toMatchObject({ status: 'SUCCESS', utr: expect.stringMatching(/^SIMN/) });
    await user.click(screen.getByRole('button', { name: 'Send simulated callback' }));
    expect(await screen.findByText(/Duplicate: this event id was already received/)).toBeInTheDocument();
  });
});
