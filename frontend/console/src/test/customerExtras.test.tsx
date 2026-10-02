import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMockServer, type MockServer } from '../mock/server';
import { documentNumberProblem, kycFileProblem } from '../pages/customers/KycTab';
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

const cust = (server: MockServer, firstName: string) => server.db.customers.find((x) => x.input.firstName === firstName)!;
const loanOf = (server: MockServer, firstName: string) => server.db.loans.find((l) => l.customerId === cust(server, firstName).id)!;
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);

describe('KYC documents', () => {
  it('validates the file and the document number on the client', () => {
    expect(kycFileProblem(null)).toBe('Choose a file');
    expect(kycFileProblem({ type: 'text/plain', size: 10 })).toBe('The file must be a PDF, JPEG or PNG');
    expect(kycFileProblem({ type: 'application/pdf', size: 5 * 1024 * 1024 + 1 })).toMatch(/the limit is 5 MB/);
    expect(kycFileProblem({ type: 'image/jpeg', size: 5 * 1024 * 1024 })).toBeNull();
    expect(documentNumberProblem('PAN', '1234 5678 9012')).toMatch(/Never enter a full Aadhaar number/);
    expect(documentNumberProblem('AADHAAR_MASKED', '12345')).toMatch(/only the last four digits/);
    expect(documentNumberProblem('AADHAAR_MASKED', '9012')).toBeNull();
    expect(documentNumberProblem('PAN', 'BBBPZ2345D')).toBeNull();
  });

  it('uploads a document as the raw body with the number in a header, and never keeps the number', async () => {
    const user = userEvent.setup({ applyAccept: false });
    const server = createMockServer();
    const biju = cust(server, 'Biju');
    const seen: Request[] = [];
    const spy: MockServer = { ...server, fetch: (r) => (seen.push(r.clone()), server.fetch(r)) };
    renderApp({ user: 'maker', route: `/customers/${biju.id}`, server: spy });
    await user.click(await screen.findByRole('tab', { name: 'KYC documents' }));
    expect(await screen.findByText('No KYC documents on file')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Upload document' }));
    const d = await screen.findByRole('dialog', { name: 'Upload KYC document' });

    // wrong type and a full Aadhaar number are stopped before anything is sent
    await within(within(d).getByLabelText(/^Document type/)).findByRole('option', { name: 'Pan', hidden: true });
    await user.selectOptions(within(d).getByLabelText(/^Document type/), 'PAN');
    await user.upload(within(d).getByLabelText(/^File/), new File(['hello'], 'pan.txt', { type: 'text/plain' }));
    expect(within(d).getByText('The file must be a PDF, JPEG or PNG')).toBeInTheDocument();
    await user.upload(within(d).getByLabelText(/^File/), new File([PNG], 'pan.png', { type: 'image/png' }));
    const number = within(d).getByLabelText('Document number') as HTMLInputElement;
    await user.type(number, '123456789012');
    await user.click(within(d).getByRole('button', { name: 'Upload' }));
    expect(within(d).getByText(/Never enter a full Aadhaar number/)).toBeInTheDocument();
    expect(seen.some((r) => r.method === 'POST' && r.url.includes('kyc-documents'))).toBe(false);

    await user.clear(number);
    await user.type(number, 'BBBPZ2345D');
    await user.type(within(d).getByLabelText('Expiry date'), '2030-12-31');
    await user.click(within(d).getByRole('button', { name: 'Upload' }));
    expect(await screen.findByText(/Pan uploaded; it is pending verification by another user/)).toBeInTheDocument();

    const post = seen.find((r) => r.method === 'POST' && r.url.includes('kyc-documents'))!;
    const url = new URL(post.url);
    expect(url.searchParams.get('docType')).toBe('PAN');
    expect(url.searchParams.get('expiryDate')).toBe('2030-12-31');
    expect(post.url).not.toContain('BBBPZ2345D');
    expect(post.headers.get('X-Document-Number')).toBe('BBBPZ2345D');
    expect(post.headers.get('Content-Type')).toBe('image/png');
    expect(new Uint8Array(await post.arrayBuffer())).toEqual(PNG);

    const row = (await screen.findByText('XXXXXX345D')).closest('tr')!;
    expect(row).toHaveTextContent('Pending');
    expect(document.body.innerHTML).not.toContain('BBBPZ2345D');
    expect(JSON.stringify(server.db.kycDocuments.map((x) => x.meta))).not.toContain('BBBPZ2345D');
    // the maker uploads but cannot verify
    expect(within(row).queryByRole('button', { name: 'Verify Pan' })).not.toBeInTheDocument();
  });

  it('a checker verifies and rejects; the uploader is refused; opening the file goes through a blob', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const anu = cust(server, 'Anu');
    renderApp({ user: 'checker', route: `/customers/${anu.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'KYC documents' }));
    const docs = await screen.findByRole('table', { name: 'KYC documents' });
    const passport = (await within(docs).findByText('Passport')).closest('tr')!;
    expect(passport).toHaveTextContent('Expired');
    expect(screen.queryByRole('button', { name: 'Upload document' })).not.toBeInTheDocument();

    await user.click(within(passport).getByRole('button', { name: 'Open Passport file' }));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].name).toMatch(/^kyc-passport-.{4}\.pdf$/);
    expect(server.db.audit.some((a) => a.action === 'KYC_DOCUMENT_DOWNLOADED' && a.actor === 'checker')).toBe(true);

    // an expired document cannot be verified: the 409 is shown
    await user.click(within(passport).getByRole('button', { name: 'Verify Passport' }));
    const v = await screen.findByRole('dialog', { name: 'Verify Passport' });
    await user.click(within(v).getByRole('button', { name: 'Verify document' }));
    expect(await within(v).findByText('Document has expired')).toBeInTheDocument();
    await user.click(within(v).getByRole('button', { name: 'Cancel' }));

    await user.click(within(passport).getByRole('button', { name: 'Reject Passport' }));
    const r = await screen.findByRole('dialog', { name: 'Reject Passport' });
    await user.click(within(r).getByRole('button', { name: 'Reject document' }));
    expect(within(r).getByText('A reason is required')).toBeInTheDocument();
    await user.type(within(r).getByLabelText(/^Reason/), 'CLAUDE-TEST expired passport');
    await user.click(within(r).getByRole('button', { name: 'Reject document' }));
    expect(await screen.findByText(/Passport rejected/)).toBeInTheDocument();
    await waitFor(() => expect(within(docs).getByText('Passport').closest('tr')).toHaveTextContent('Rejected CLAUDE-TEST expired passport'));

    // uploader ≠ verifier, and Aadhaar needs the masking confirmation
    const up = (u: string, docType: string, number: string) =>
      server.fetch(new Request(`http://localhost/api/v1/customers/${anu.id}/kyc-documents?docType=${docType}`, { method: 'POST', headers: { Authorization: `Bearer mock.${u}`, 'Content-Type': 'image/png', 'X-Document-Number': number }, body: PNG }));
    const own = await (await up('admin', 'aadhaar-masked', '9012')).json();
    expect(own).toMatchObject({ docType: 'AADHAAR_MASKED', numberMasked: 'XXXX XXXX 9012', status: 'PENDING' });
    expect((await mockCall(server, 'admin', 'POST', `/api/v1/customers/${anu.id}/kyc-documents/${own.id}/verify`, { maskingConfirmed: true })).status).toBe(403);
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/customers/${anu.id}/kyc-documents/${own.id}/verify`, {})).status).toBe(422);
    expect((await mockCall(server, 'checker', 'POST', `/api/v1/customers/${anu.id}/kyc-documents/${own.id}/verify`, { maskingConfirmed: true })).body).toMatchObject({ status: 'VERIFIED', customerKycStatus: 'VERIFIED' });
    expect((await up('maker', 'PAN', '1234 5678 9012')).status).toBe(422);
    const lying = await server.fetch(new Request(`http://localhost/api/v1/customers/${anu.id}/kyc-documents?docType=PAN`, { method: 'POST', headers: { Authorization: 'Bearer mock.maker', 'Content-Type': 'application/pdf' }, body: PNG }));
    expect(lying.status).toBe(415);
  });

  it('shows the refusal when the uploader tries to verify their own upload', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const biju = cust(server, 'Biju');
    await server.fetch(new Request(`http://localhost/api/v1/customers/${biju.id}/kyc-documents?docType=PHOTO`, { method: 'POST', headers: { Authorization: 'Bearer mock.admin', 'Content-Type': 'image/png' }, body: PNG }));
    renderApp({ user: 'admin', route: `/customers/${biju.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'KYC documents' }));
    await user.click(await screen.findByRole('button', { name: 'Verify Photo' }));
    const v = await screen.findByRole('dialog', { name: 'Verify Photo' });
    await user.click(within(v).getByRole('button', { name: 'Verify document' }));
    expect(await within(v).findByText('Uploader cannot verify')).toBeInTheDocument();
  });
});

describe('consents', () => {
  it('records a consent and withdraws it with a reason', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const biju = cust(server, 'Biju');
    renderApp({ user: 'maker', route: `/customers/${biju.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'Consents' }));
    expect(await screen.findByText('No consent records')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Record consent' }));
    const d = await screen.findByRole('dialog', { name: 'Record consent' });
    await within(within(d).getByLabelText(/^Purpose/)).findByRole('option', { name: 'Marketing', hidden: true });
    await user.selectOptions(within(d).getByLabelText(/^Purpose/), 'MARKETING');
    await user.type(within(d).getByLabelText(/^Notice version/), 'PN-2026.2');
    await user.click(within(d).getByRole('button', { name: 'Record' }));
    expect(within(d).getByText('Evidence is required for a consent')).toBeInTheDocument();
    await user.type(within(d).getByLabelText(/^Evidence reference/), 'OTP-CLAUDE-TEST-77');
    await user.click(within(d).getByRole('button', { name: 'Record' }));
    expect(await screen.findByText('Marketing recorded.')).toBeInTheDocument();

    const table = screen.getByRole('table', { name: 'Consent records' });
    const row = (await within(table).findByText('Marketing')).closest('tr')!;
    expect(row).toHaveTextContent('Consent (s.6)');
    expect(row).toHaveTextContent('PN-2026.2');
    expect(row).toHaveTextContent('In force');
    await user.click(within(row).getByRole('button', { name: 'Withdraw consent for Marketing' }));
    const w = await screen.findByRole('dialog', { name: 'Withdraw consent: Marketing' });
    await user.type(within(w).getByLabelText(/^Reason/), 'CLAUDE-TEST customer request');
    await user.click(within(w).getByRole('button', { name: 'Record withdrawal' }));
    expect(await screen.findByText('Withdrawal recorded.')).toBeInTheDocument();
    await waitFor(() => expect(within(table).getByText('Marketing').closest('tr')).toHaveTextContent('Withdrawn'));
    expect(within(table).getByText('Marketing').closest('tr')).toHaveTextContent('by maker: CLAUDE-TEST customer request');
  });

  it('marks a withdrawn servicing consent as retained for legal obligation; legitimate use cannot be withdrawn', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const anu = cust(server, 'Anu');
    renderApp({ user: 'maker', route: `/customers/${anu.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'Consents' }));
    const table = await screen.findByRole('table', { name: 'Consent records' });
    const bureau = (await within(table).findByText('Credit bureau reporting')).closest('tr')!;
    expect(bureau).toHaveTextContent('Legitimate use (s.7)');
    expect(within(bureau).queryByRole('button')).not.toBeInTheDocument();
    expect(within(table).getByText('Marketing').closest('tr')).toHaveTextContent('Expired');
    await user.click(screen.getByRole('button', { name: 'Withdraw consent for Loan processing' }));
    const w = await screen.findByRole('dialog', { name: /Withdraw consent/ });
    await user.type(within(w).getByLabelText(/^Reason/), 'CLAUDE-TEST');
    await user.click(within(w).getByRole('button', { name: 'Record withdrawal' }));
    expect(await screen.findByText(/retained for legal obligation while a loan exists/)).toBeInTheDocument();
    expect(await within(table).findByText('Retained for legal obligation')).toBeInTheDocument();
    const lu = server.db.consents.find((c) => c.lawfulBasis === 'LEGITIMATE_USE')!;
    expect((await mockCall(server, 'maker', 'POST', `/api/v1/customers/${anu.id}/consents/${lu.id}/withdraw`, { reason: 'x' })).status).toBe(409);
  });
});

describe('relationships and exposure', () => {
  it('shows exposure with limit and headroom, and proposes a new limit', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const anu = cust(server, 'Anu');
    renderApp({ user: 'maker', route: `/customers/${anu.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'Relationships & exposure' }));
    const e = await screen.findByTestId('exposure');
    expect(e).toHaveTextContent('As borrower₹1,63,307.001 loan(s)');
    expect(e).toHaveTextContent('Exposure limit₹5,00,000.00');
    expect(e).toHaveTextContent('Headroom₹3,36,693.00');
    await user.click(screen.getByRole('button', { name: 'Set exposure limit' }));
    const d = await screen.findByRole('dialog', { name: /Exposure limit for Anu/ });
    await user.clear(within(d).getByLabelText('Exposure limit'));
    await user.type(within(d).getByLabelText('Exposure limit'), '750000');
    await user.type(within(d).getByLabelText(/^Reason/), 'CLAUDE-TEST income reassessed');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Exposure limit sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((s) => s.approval.entityType === 'EXPOSURE_LIMIT')!;
    expect(a.approval).toMatchObject({ action: 'UPDATE', current: { exposureLimit: '500000.00' }, proposed: { exposureLimit: '750000.00' } });
    // The limit in force stops a loan that would exceed it
    const over = await mockCall(server, 'maker', 'POST', '/api/v1/loans', { productCode: 'PL01', customerId: anu.id, amount: '400000', tenorMonths: 24 });
    expect(over.status).toBe(409);
    expect(over.body.title).toBe('Exposure limit exceeded');
  });

  it('proposes a guarantor picked by customer search; approval adds the relationship', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const gauri = cust(server, 'Gauri');
    renderApp({ user: 'maker', route: `/customers/${gauri.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'Relationships & exposure' }));
    expect(await screen.findByText('No relationships')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add relationships' }));
    const d = await screen.findByRole('dialog', { name: /Add relationships of Gauri/ });
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('Add at least one relationship')).toBeInTheDocument();
    await user.type(within(d).getByLabelText('Find the related customer'), cust(server, 'Hari').customerNo);
    await user.click(within(d).getByRole('button', { name: 'Search' }));
    await user.click(await within(d).findByRole('button', { name: 'Add Hari CLAUDE-TEST as guarantor' }));
    await user.selectOptions(within(d).getByLabelText('Relationship'), 'NOMINEE');
    await user.click(within(d).getByRole('button', { name: 'Add Hari CLAUDE-TEST as nominee' }));
    expect(within(d).getByLabelText('Share % of Hari CLAUDE-TEST')).toHaveValue('100');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('2 relationship(s) sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((s) => s.approval.entityType === 'CUSTOMER_RELATIONSHIP')!;
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
    const rels = (await mockCall(server, 'maker', 'GET', `/api/v1/customers/${gauri.id}/relationships`)).body;
    expect(rels.map((r: { relationType: string; sharePercent: string | null }) => [r.relationType, r.sharePercent])).toEqual([['GUARANTOR', null], ['NOMINEE', '100']]);
    // nominee shares must total 100
    const bad = await mockCall(server, 'maker', 'POST', `/api/v1/customers/${gauri.id}/relationships`, { relationships: [{ relatedCustomerId: cust(server, 'Hari').id, relationType: 'NOMINEE', sharePercent: 60 }] });
    expect(bad.status).toBe(422);
  });

  it('hides proposing, uploading and recording from a checker', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const anu = cust(server, 'Anu');
    renderApp({ user: 'checker', route: `/customers/${anu.id}`, server });
    await user.click(await screen.findByRole('tab', { name: 'Relationships & exposure' }));
    expect(await screen.findByTestId('exposure')).toBeInTheDocument();
    expect((await screen.findAllByText('Biju CLAUDE-TEST')).length).toBe(2); // co-applicant and nominee
    for (const name of ['Set exposure limit', 'Add relationships', 'Upload document', 'Record consent']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Consents' }));
    await screen.findByRole('table', { name: 'Consent records' });
    expect(screen.queryByRole('button', { name: /Withdraw consent/ })).not.toBeInTheDocument();
  });
});

describe('loan parties', () => {
  it('adds a guarantor on the new-loan form and shows the parties on the loan', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    renderApp({ user: 'maker', route: '/loans/new', server });
    await user.click(await screen.findByRole('button', { name: 'Select Hari CLAUDE-TEST' }));
    await user.type(screen.getByLabelText('Find a co-applicant or guarantor'), cust(server, 'Biju').customerNo);
    await user.click(screen.getByRole('button', { name: 'Search parties' }));
    await user.click(await screen.findByRole('button', { name: 'Add Biju CLAUDE-TEST as guarantor' }));
    expect(within(screen.getByRole('list', { name: 'Parties added' })).getByText(/Guarantor/)).toBeInTheDocument();
    await within(screen.getByLabelText(/^Product/)).findByRole('option', { name: /PL01/, hidden: true });
    await user.selectOptions(screen.getByLabelText(/^Product/), 'PL01');
    await user.type(screen.getByLabelText(/^Amount/), '50000');
    await user.type(screen.getByLabelText(/^Tenor/), '12');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    await screen.findByTestId('kfs');
    await user.click(screen.getByRole('button', { name: 'Create loan' }));
    expect(await screen.findByRole('heading', { name: /^Loan 1001/ })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Parties' }));
    const parties = await screen.findByRole('table', { name: 'Loan parties' });
    const rows = within(parties).getAllByRole('row').slice(1);
    expect(rows.map((r) => r.textContent)).toEqual([expect.stringMatching(/^BorrowerHari CLAUDE-TEST/), expect.stringMatching(/^GuarantorBiju CLAUDE-TEST/)]);
    // exposure of the guarantor now includes this loan
    expect((await mockCall(server, 'maker', 'GET', `/api/v1/customers/${cust(server, 'Biju').id}/exposure`)).body).toMatchObject({ asGuarantor: '50000.00', loansAsGuarantor: 1 });
    const dup = await mockCall(server, 'maker', 'POST', '/api/v1/loans/preview', { productCode: 'PL01', customerId: cust(server, 'Hari').id, amount: '50000', tenorMonths: 12, parties: [{ customerId: cust(server, 'Hari').id, role: 'GUARANTOR' }] });
    expect(dup.status).toBe(422);
  });
});

describe('role amount limits', () => {
  it('lists limits in force and proposes a change', async () => {
    const user = userEvent.setup();
    const { server } = renderApp({ user: 'maker', route: '/masters/amount-limits' });
    expect(await screen.findByRole('heading', { name: 'Role amount limits' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Role amount limits' });
    const repay = (await within(table).findByText('Loan repayment')).closest('tr')!;
    expect(repay).toHaveTextContent('MAKER');
    expect(repay).toHaveTextContent('₹5,00,000.00');
    expect(repay).toHaveTextContent('No day limit');
    expect(within(table).getAllByRole('row')).toHaveLength(7);
    await user.click(screen.getByRole('checkbox', { name: 'Only limits in force today' }));
    // the list reloads for the new filter
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Role amount limits' })).getAllByRole('row')).toHaveLength(8));
    expect(screen.getByText('Ended')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Propose limit' }));
    const d = await screen.findByRole('dialog', { name: 'Propose amount limit' });
    await user.type(within(d).getByLabelText(/^Role/), 'CHECKER');
    await user.selectOptions(within(d).getByLabelText('Transaction type'), 'LOAN_PRECLOSURE');
    await user.type(within(d).getByLabelText(/^Per transaction/), '2000000');
    await user.type(within(d).getByLabelText('Per day'), '1000000');
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(within(d).getByText('Cannot be below the per-transaction limit')).toBeInTheDocument();
    await user.clear(within(d).getByLabelText('Per day'));
    await user.click(within(d).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Amount limit sent for approval.')).toBeInTheDocument();
    const a = server.db.approvals.find((s) => s.approval.entityType === 'AMOUNT_LIMIT')!;
    expect(a.approval.proposed).toMatchObject({ roleName: 'CHECKER', txnType: 'LOAN_PRECLOSURE', perTransactionMax: '2000000.00', perDayMax: null, effectiveFrom: '2026-06-30' });
    await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${a.approval.id}/approve`, {});
    expect((await mockCall(server, 'maker', 'GET', '/api/v1/amount-limits?roleName=CHECKER&txnType=LOAN_PRECLOSURE')).body).toMatchObject([{ inForce: true }]);
  });

  it('hides Propose from a checker', async () => {
    renderApp({ user: 'checker', route: '/masters/amount-limits' });
    await screen.findByRole('table', { name: 'Role amount limits' });
    await screen.findByText('Loan repayment');
    expect(screen.queryByRole('button', { name: 'Propose limit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Change / })).not.toBeInTheDocument();
  });

  it('shows the limit refusal (403) in the repayment dialog', async () => {
    const user = userEvent.setup();
    const server = createMockServer();
    const loan = loanOf(server, 'Anu');
    renderApp({ user: 'maker', route: `/loans/${loan.id}`, server });
    await user.click(await screen.findByRole('button', { name: 'Repayment' }));
    const d = await screen.findByRole('dialog', { name: /Repayment on/ });
    await user.clear(within(d).getByLabelText(/^Amount/));
    await user.type(within(d).getByLabelText(/^Amount/), '600000');
    await user.click(within(d).getByRole('button', { name: 'Record repayment' }));
    expect(await within(d).findByText('Above your amount limit')).toBeInTheDocument();
    expect(within(d).getByText(/₹6,00,000.00 is above the loan repayment limit of ₹5,00,000.00 per transaction for role MAKER/)).toBeInTheDocument();
    // an approval above the checker's limit is refused too
    const big = await mockCall(server, 'maker', 'POST', '/api/v1/gl/vouchers', {
      voucherType: 'JOURNAL', valueDate: '2026-06-30', description: 'CLAUDE-TEST over the checker limit',
      lines: [{ branch: 'HO', glCode: '1210', side: 'DR', amount: '4000000.00' }, { branch: 'HO', glCode: '4101', side: 'CR', amount: '4000000.00' }],
    });
    expect(big.status).toBe(202);
    server.db.amountLimits.find((l) => l.roleName === 'CHECKER' && l.txnType === 'VOUCHER')!.perTransactionMax = '3000000.00';
    const refused = await mockCall(server, 'checker', 'POST', `/api/v1/approvals/${big.body.id}/approve`, {});
    expect(refused.status).toBe(403);
    expect(refused.body.detail).toMatch(/A checker with a higher limit must approve/);
  });
});
