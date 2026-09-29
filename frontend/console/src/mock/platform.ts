/**
 * Phase 1 master data in the mock API: staff and branch scope, branch sets, system properties, enumerations and
 * territory (states, pincodes, territory file). Every change goes through maker-checker like the other masters.
 */
import type { BranchSet, EnumValueInput, PincodePlace, Staff, State } from '../api/types';
import type { DemoUser } from '../auth/demoUsers';
import { DEMO_USERS } from '../auth/demoUsers';
import { P } from '../auth/permissions';
import { parseCsv } from '../lib/csv';
import { PINCODE_PATTERN } from '../lib/mask';
import { appendAudit, type ApprovalPayload, type MockDb, type StoredEnumValue, type StoredStaff } from './db';
import { headByCode } from './ledger';
import { bad, conflict, HttpProblem, notFound, PROBLEM_BASE, type FieldProblem } from './problems';

// ------------------------------------------------------------------ seed data
const ENUMS: Record<string, Array<[code: string, label: string]>> = {
  'customer-type': [['INDIVIDUAL', 'Individual'], ['NON_INDIVIDUAL', 'Non-individual']],
  gender: [['FEMALE', 'Female'], ['MALE', 'Male'], ['OTHER', 'Other']],
  'voucher-type': [['CONTRA', 'Contra'], ['RECEIPT', 'Receipt'], ['PAYMENT', 'Payment'], ['JOURNAL', 'Journal']],
  'gst-state': [
    ['01', 'Jammu and Kashmir'], ['06', 'Haryana'], ['07', 'Delhi'], ['08', 'Rajasthan'], ['09', 'Uttar Pradesh'], ['19', 'West Bengal'],
    ['24', 'Gujarat'], ['27', 'Maharashtra'], ['29', 'Karnataka'], ['32', 'Kerala'], ['33', 'Tamil Nadu'], ['36', 'Telangana'], ['37', 'Andhra Pradesh'],
  ],
  'loan-purpose': [['EDUCATION', 'Education'], ['MEDICAL', 'Medical'], ['TRAVEL', 'Travel'], ['HOME_RENOVATION', 'Home renovation'], ['OTHER', 'Other']],
  'repayment-mode': [['NACH', 'NACH mandate'], ['UPI', 'UPI'], ['CASH', 'Cash at branch'], ['NEFT', 'NEFT/RTGS']],
};

type St = [code: string, name: string, gst: string, ut: boolean];
const STATES: St[] = [
  ['AN', 'Andaman and Nicobar Islands', '35', true], ['AP', 'Andhra Pradesh', '37', false], ['AR', 'Arunachal Pradesh', '12', false],
  ['AS', 'Assam', '18', false], ['BR', 'Bihar', '10', false], ['CH', 'Chandigarh', '04', true], ['CG', 'Chhattisgarh', '22', false],
  ['DH', 'Dadra and Nagar Haveli and Daman and Diu', '26', true], ['DL', 'Delhi', '07', true], ['GA', 'Goa', '30', false],
  ['GJ', 'Gujarat', '24', false], ['HR', 'Haryana', '06', false], ['HP', 'Himachal Pradesh', '02', false], ['JK', 'Jammu and Kashmir', '01', true],
  ['JH', 'Jharkhand', '20', false], ['KA', 'Karnataka', '29', false], ['KL', 'Kerala', '32', false], ['LA', 'Ladakh', '38', true],
  ['LD', 'Lakshadweep', '31', true], ['MP', 'Madhya Pradesh', '23', false], ['MH', 'Maharashtra', '27', false], ['MN', 'Manipur', '14', false],
  ['ML', 'Meghalaya', '17', false], ['MZ', 'Mizoram', '15', false], ['NL', 'Nagaland', '13', false], ['OD', 'Odisha', '21', false],
  ['PY', 'Puducherry', '34', true], ['PB', 'Punjab', '03', false], ['RJ', 'Rajasthan', '08', false], ['SK', 'Sikkim', '11', false],
  ['TN', 'Tamil Nadu', '33', false], ['TS', 'Telangana', '36', false], ['TR', 'Tripura', '16', false], ['UP', 'Uttar Pradesh', '09', false],
  ['UK', 'Uttarakhand', '05', false], ['WB', 'West Bengal', '19', false],
];

type Pin = [pincode: string, city: string, district: string, state: string];
const PINCODES: Pin[] = [
  ['682001', 'Kochi (Fort Kochi)', 'Ernakulam', 'KL'],
  ['682001', 'Mattancherry', 'Ernakulam', 'KL'],
  ['682011', 'Ernakulam North', 'Ernakulam', 'KL'],
  ['695001', 'Thiruvananthapuram', 'Thiruvananthapuram', 'KL'],
  ['400053', 'Andheri West', 'Mumbai Suburban', 'MH'],
  ['400001', 'Fort', 'Mumbai', 'MH'],
  ['560001', 'Bengaluru', 'Bengaluru Urban', 'KA'],
  ['600001', 'Chennai', 'Chennai', 'TN'],
  ['110001', 'New Delhi', 'New Delhi', 'DL'],
];

const PROPERTIES: Array<[key: string, value: string, description: string]> = [
  ['approval.sla-hours', '48', 'Hours after which a pending approval is shown as overdue'],
  ['customer.min-age', '18', 'Minimum age of an individual customer'],
  ['gst.supplier-state', '32', 'GST state code of the registered office (place of supply fallback)'],
  ['lending.disbursement-gl', '1110', 'Bank GL head credited on disbursement'],
  ['lending.penal-income-gl', '4202', 'Income GL head for penal charges'],
  ['lending.cooling-off-default-days', '3', 'Cooling-off days for products that do not set their own'],
];

export function seedPlatform(db: MockDb, seedAt: string) {
  db.enumerations = Object.fromEntries(
    Object.entries(ENUMS).map(([type, values]) => [type, values.map(([code, label], i) => ({ code, label, sortOrder: (i + 1) * 10, active: true }))]),
  );
  db.states = STATES.map(([code, name, gstStateCode, unionTerritory]) => ({ code, name, gstStateCode, unionTerritory }));
  db.pincodes = PINCODES.map(([pincode, city, district, state]) => placeOf(db, pincode, city, district, state));
  db.systemProperties = PROPERTIES.map(([key, value, description]) => ({ key, value, description, updatedBy: 'system', updatedAt: seedAt }));
  db.branchSets = [
    { code: 'SOUTH', name: 'South zone', branches: ['HO'] },
    { code: 'WEST', name: 'West zone', branches: ['MUM'] },
  ];
  const scope: Record<string, Partial<StoredStaff>> = {
    maker: { branches: ['MUM'] },
    checker: { allBranches: true },
    auditor: { branchSets: ['SOUTH'] },
    admin: { allBranches: true },
  };
  db.staff = [
    ...DEMO_USERS.map((u) => staffRecord({ username: u.username, displayName: u.name, homeBranch: u.homeBranch, ...scope[u.username] })),
    staffRecord({ username: 'lakshmi.n', displayName: 'Lakshmi Nair', homeBranch: 'HO' }),
    staffRecord({ username: 'rahul.m', displayName: 'Rahul Mehta', homeBranch: 'MUM', status: 'EXITED' }),
  ];
}

function staffRecord(s: Partial<StoredStaff> & Pick<StoredStaff, 'username' | 'displayName' | 'homeBranch'>): StoredStaff {
  return { allBranches: false, branches: [], branchSets: [], status: 'ACTIVE', userId: s.username.toLowerCase(), ...s };
}

function placeOf(db: MockDb, pincode: string, city: string, district: string, stateCode: string): PincodePlace {
  const st = db.states.find((s) => s.code === stateCode)!;
  return { pincode, city, district, stateCode: st.code, stateName: st.name, gstStateCode: st.gstStateCode };
}

// ------------------------------------------------------------------ branch scope
export interface BranchScope {
  allBranches: boolean;
  branches: string[];
}

/** Branches a user can see: all, or home + granted branches + members of granted branch sets. */
export function branchScope(db: MockDb, username: string, fallbackHome?: string): BranchScope {
  const s = db.staff.find((x) => x.username === username);
  if (!s) return { allBranches: false, branches: fallbackHome ? [fallbackHome] : [] };
  if (s.allBranches) return { allBranches: true, branches: db.branches.map((b) => b.code).sort() };
  const set = new Set<string>([s.homeBranch, ...s.branches]);
  for (const code of s.branchSets) for (const b of db.branchSets.find((x) => x.code === code)?.branches ?? []) set.add(b);
  return { allBranches: false, branches: [...set].sort() };
}

// ------------------------------------------------------------------ approvals
export function applyPlatformApproval(db: MockDb, p: ApprovalPayload, approval: { entityId?: string | null }, checker: string, at: string): boolean {
  switch (p.kind) {
    case 'STAFF': {
      const i = db.staff.findIndex((s) => s.username === p.staff.username);
      if (i >= 0) db.staff[i] = { ...p.staff };
      else db.staff.push({ ...p.staff });
      approval.entityId = p.staff.username;
      return true;
    }
    case 'BRANCH_SET': {
      const i = db.branchSets.findIndex((s) => s.code === p.set.code);
      if (i >= 0) db.branchSets[i] = { ...p.set };
      else db.branchSets.push({ ...p.set });
      approval.entityId = p.set.code;
      return true;
    }
    case 'SYSTEM_PROPERTY': {
      const i = db.systemProperties.findIndex((x) => x.key === p.key);
      const rec = { key: p.key, value: p.value, description: p.description ?? db.systemProperties[i]?.description ?? null, updatedBy: checker, updatedAt: at };
      if (i >= 0) db.systemProperties[i] = rec;
      else db.systemProperties.push(rec);
      approval.entityId = p.key;
      return true;
    }
    case 'ENUMERATION': {
      const list = db.enumerations[p.type] ?? (db.enumerations[p.type] = []);
      for (const v of p.values) {
        const i = list.findIndex((x) => x.code === v.code);
        if (i >= 0) list[i] = { ...v };
        else list.push({ ...v });
      }
      approval.entityId = p.type;
      return true;
    }
    case 'TERRITORY': {
      for (const place of p.places) {
        const i = db.pincodes.findIndex((x) => x.pincode === place.pincode && x.city?.toLowerCase() === place.city?.toLowerCase());
        if (i >= 0) db.pincodes[i] = { ...place };
        else db.pincodes.push({ ...place });
      }
      appendAudit(db, at, checker, 'TERRITORY_LOADED', 'TERRITORY', null, { rows: p.places.length });
      return true;
    }
    default:
      return false;
  }
}

// ------------------------------------------------------------------ CSV helpers (shared with the other uploads)
/** Parses an uploaded CSV body; checks required/optional columns and the row limit (413 above it). */
export function readCsvUpload(body: unknown, required: string[], optional: string[], maxRows: number): Array<Record<string, string>> {
  if (typeof body !== 'string' || !body.trim()) throw bad('The file is empty', [{ field: 'file', message: 'Upload a CSV file with a header row' }]);
  const [header, ...rows] = parseCsv(body);
  const cols = (header ?? []).map((h) => h.trim());
  const missing = required.filter((c) => !cols.includes(c));
  const unknown = cols.filter((c) => !required.includes(c) && !optional.includes(c));
  if (missing.length || unknown.length) {
    const msg = [missing.length && `missing column(s): ${missing.join(', ')}`, unknown.length && `unknown column(s): ${unknown.join(', ')}`].filter(Boolean).join('; ');
    throw bad(`Header row: ${msg}. Expected ${[...required, ...optional.map((o) => `[${o}]`)].join(', ')}`, [{ field: 'header', message: msg }]);
  }
  if (rows.length === 0) throw bad('The file has a header but no data rows', [{ field: 'file', message: 'No data rows' }]);
  if (rows.length > maxRows) {
    throw new HttpProblem(413, 'File too large', `The file has ${rows.length} rows; at most ${maxRows.toLocaleString('en-IN')} are allowed per upload`, { maxRows }, PROBLEM_BASE + 'payload-too-large');
  }
  return rows.map((r) => Object.fromEntries(cols.map((c, i) => [c, (r[i] ?? '').trim()])));
}

/** 422 listing the first row errors. */
export function rowErrors(errors: FieldProblem[]): HttpProblem {
  const shown = errors.slice(0, 50);
  return bad(`${errors.length} problem(s) in the file; nothing was loaded. ${shown.slice(0, 3).map((e) => e.message).join('; ')}${errors.length > 3 ? '; …' : ''}`, shown);
}

// ------------------------------------------------------------------ routes
type Result = { status: number; body: unknown };
interface Ctx {
  user: DemoUser;
  url: URL;
  body: unknown;
  params: Record<string, string>;
}
export interface PlatformRouter {
  on: (method: string, path: string, handler: (ctx: Ctx) => Result) => void;
  require: (user: DemoUser, perm: string) => void;
  propose: (
    user: DemoUser,
    entityType: string,
    action: string,
    payload: ApprovalPayload,
    proposed: Record<string, unknown>,
    current?: Record<string, unknown> | null,
    entityId?: string | null,
    amount?: string | null,
  ) => Result;
}

const ok = (body: unknown): Result => ({ status: 200, body });
const ENUM_TYPE_PATTERN = /^[a-z][a-z0-9-]{1,40}$/;
const PROPERTY_KEY_PATTERN = /^[a-z][a-z0-9_.-]+$/;
const USERNAME_PATTERN = /^[A-Za-z0-9._@-]{2,80}$/;

export function registerPlatformRoutes(db: MockDb, r: PlatformRouter) {
  const { on, require, propose } = r;
  const pending = (kind: ApprovalPayload['kind'], pred: (p: ApprovalPayload) => boolean) =>
    db.approvals.find((s) => s.approval.status === 'PENDING' && s.payload.kind === kind && pred(s.payload));
  const branchExists = (code: string) => db.branches.some((b) => b.code === code);

  // ---- enumerations
  on('GET', '/api/v1/enumerations', ({ user }) => {
    require(user, P.masterView);
    return ok(
      Object.entries(db.enumerations)
        .map(([type, values]) => ({ type, valueCount: values.length, activeCount: values.filter((v) => v.active).length }))
        .sort((a, b) => a.type.localeCompare(b.type)),
    );
  });
  on('GET', '/api/v1/enumerations/{type}', ({ params }) => {
    const values = db.enumerations[params.type];
    if (!values) throw notFound(`Enumeration ${params.type}`);
    return ok([...values].sort((a, b) => a.sortOrder - b.sortOrder || a.code.localeCompare(b.code)).map(({ code, label, active, sortOrder }) => ({ code, label, active, sortOrder })));
  });
  on('POST', '/api/v1/enumerations/{type}', ({ user, params, body }) => {
    require(user, P.masterPropose);
    const type = params.type;
    const existing = db.enumerations[type];
    if (!ENUM_TYPE_PATTERN.test(type)) throw bad('Type must be lower-case letters, digits or - (2-41 characters, starting with a letter)', [{ field: 'type', message: 'Invalid type' }]);
    const list = body as EnumValueInput[];
    if (!Array.isArray(list) || list.length === 0) throw bad('At least one value is required');
    if (list.length > 500) throw bad('At most 500 values per request');
    const errors: FieldProblem[] = [];
    const seen = new Set<string>();
    list.forEach((v, i) => {
      if (!/^[A-Z0-9_]{1,40}$/.test(v?.code ?? '')) errors.push({ field: `[${i}].code`, message: `Row ${i + 1}: code must be upper-case letters, digits or _` });
      else if (seen.has(v.code)) errors.push({ field: `[${i}].code`, message: `Row ${i + 1}: duplicate code ${v.code}` });
      seen.add(v?.code);
      if (!v?.label?.trim()) errors.push({ field: `[${i}].label`, message: `Row ${i + 1}: label is required` });
      if (v?.sortOrder !== undefined && !Number.isInteger(v.sortOrder)) errors.push({ field: `[${i}].sortOrder`, message: `Row ${i + 1}: sort order must be a whole number` });
    });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (pending('ENUMERATION', (p) => p.kind === 'ENUMERATION' && p.type === type)) throw conflict('Change already pending', `Enumeration ${type} already has a pending change`);
    const values: StoredEnumValue[] = list.map((v) => ({ code: v.code, label: v.label.trim(), sortOrder: v.sortOrder ?? 0, active: v.active ?? true }));
    const changed = values.filter((v) => {
      const e = existing?.find((x) => x.code === v.code);
      return !e || e.label !== v.label || e.sortOrder !== v.sortOrder || e.active !== v.active;
    });
    if (changed.length === 0) throw bad('Nothing to change: every value is the same as today');
    const current = existing ? existing.filter((e) => changed.some((c) => c.code === e.code)) : [];
    return propose(user, 'ENUMERATION', existing ? 'UPDATE' : 'CREATE', { kind: 'ENUMERATION', type, values: changed }, { type, values: changed }, existing ? { type, values: current } : null, type);
  });

  // ---- system properties
  on('GET', '/api/v1/system-properties', ({ user }) => {
    require(user, P.masterView);
    return ok([...db.systemProperties].sort((a, b) => a.key.localeCompare(b.key)));
  });
  on('PUT', '/api/v1/system-properties/{key}', ({ user, params, body }) => {
    require(user, P.masterPropose);
    const key = params.key;
    if (!PROPERTY_KEY_PATTERN.test(key)) throw bad('Key must be lower-case letters, digits, dot, dash or underscore', [{ field: 'key', message: 'Invalid key' }]);
    const b = (body ?? {}) as { value?: string; description?: string };
    const value = typeof b.value === 'string' ? b.value.trim() : '';
    if (!value) throw bad('Value is required', [{ field: 'value', message: 'Required' }]);
    if (key.endsWith('-gl')) {
      const head = headByCode(db, value);
      if (!head || !head.posting || head.status !== 'ACTIVE') throw bad(`${value} is not an active posting GL head`, [{ field: 'value', message: 'Must name an active posting GL head' }]);
    }
    const existing = db.systemProperties.find((x) => x.key === key);
    if (existing && existing.value === value && (b.description === undefined || b.description === existing.description)) throw bad('Nothing to change: the value is the same as today');
    if (pending('SYSTEM_PROPERTY', (p) => p.kind === 'SYSTEM_PROPERTY' && p.key === key)) throw conflict('Change already pending', `Property ${key} already has a pending change`);
    const description = b.description?.trim() || existing?.description || null;
    return propose(
      user, 'SYSTEM_PROPERTY', existing ? 'UPDATE' : 'CREATE',
      { kind: 'SYSTEM_PROPERTY', key, value, description },
      { key, value, description },
      existing ? { key, value: existing.value, description: existing.description } : null,
      key,
    );
  });

  // ---- branch sets
  on('GET', '/api/v1/branch-sets', ({ user }) => {
    require(user, P.branchView);
    return ok([...db.branchSets].sort((a, b) => a.code.localeCompare(b.code)));
  });
  on('POST', '/api/v1/branch-sets', ({ user, body }) => {
    require(user, P.branchPropose);
    const s = body as BranchSet;
    const errors: FieldProblem[] = [];
    if (!/^[A-Z0-9_]{2,20}$/.test(s?.code ?? '')) errors.push({ field: 'code', message: 'Code must be 2-20 upper-case letters, digits or _' });
    if (!s?.name?.trim()) errors.push({ field: 'name', message: 'Name is required' });
    if (!Array.isArray(s?.branches) || s.branches.length === 0) errors.push({ field: 'branches', message: 'Select at least one branch' });
    for (const b of s?.branches ?? []) if (!branchExists(b)) errors.push({ field: 'branches', message: `Unknown branch ${b}` });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    if (pending('BRANCH_SET', (p) => p.kind === 'BRANCH_SET' && p.set.code === s.code)) throw conflict('Change already pending', `Branch set ${s.code} already has a pending change`);
    const set: BranchSet = { code: s.code, name: s.name.trim(), branches: [...new Set(s.branches)].sort() };
    const existing = db.branchSets.find((x) => x.code === s.code);
    return propose(user, 'BRANCH_SET', existing ? 'UPDATE' : 'CREATE', { kind: 'BRANCH_SET', set }, { ...set }, existing ? { ...existing } : null, existing ? set.code : null);
  });

  // ---- staff
  on('GET', '/api/v1/staff', ({ user }) => {
    require(user, P.staffView);
    return ok([...db.staff].sort((a, b) => a.username.localeCompare(b.username)));
  });
  on('POST', '/api/v1/staff', ({ user, body }) => {
    require(user, P.staffPropose);
    const s = body as Staff;
    const errors: FieldProblem[] = [];
    if (!USERNAME_PATTERN.test(s?.username ?? '')) errors.push({ field: 'username', message: 'Username must be 2-80 letters, digits or . _ @ -' });
    if (!s?.displayName?.trim()) errors.push({ field: 'displayName', message: 'Display name is required' });
    if (!s?.homeBranch || !branchExists(s.homeBranch)) errors.push({ field: 'homeBranch', message: 'Unknown home branch' });
    for (const b of s?.branches ?? []) if (!branchExists(b)) errors.push({ field: 'branches', message: `Unknown branch ${b}` });
    for (const c of s?.branchSets ?? []) if (!db.branchSets.some((x) => x.code === c)) errors.push({ field: 'branchSets', message: `Unknown branch set ${c}` });
    if (s?.status && !['ACTIVE', 'SUSPENDED', 'EXITED'].includes(s.status)) errors.push({ field: 'status', message: 'Invalid status' });
    if (errors.length) throw bad(errors.map((e) => e.message).join('; '), errors);
    // A maker can grant only branches they can see themselves.
    const mine = branchScope(db, user.username, user.homeBranch);
    if (!mine.allBranches) {
      if (s.allBranches) throw new HttpProblem(403, 'Outside your branch scope', 'Only a user who sees all branches can grant all branches', {}, PROBLEM_BASE + 'forbidden');
      const granted = new Set([s.homeBranch, ...(s.branches ?? [])]);
      for (const c of s.branchSets ?? []) for (const b of db.branchSets.find((x) => x.code === c)?.branches ?? []) granted.add(b);
      const outside = [...granted].filter((b) => !mine.branches.includes(b));
      if (outside.length) throw new HttpProblem(403, 'Outside your branch scope', `You cannot grant branch(es) you do not see: ${outside.join(', ')}`, { branches: outside }, PROBLEM_BASE + 'forbidden');
    }
    if (pending('STAFF', (p) => p.kind === 'STAFF' && p.staff.username.toLowerCase() === s.username.toLowerCase())) throw conflict('Change already pending', `Staff ${s.username} already has a pending change`);
    const existing = db.staff.find((x) => x.username.toLowerCase() === s.username.toLowerCase());
    const staff: StoredStaff = {
      userId: existing?.userId ?? s.username.toLowerCase(),
      username: existing?.username ?? s.username,
      displayName: s.displayName.trim(),
      homeBranch: s.homeBranch,
      allBranches: !!s.allBranches,
      branches: s.allBranches ? [] : [...new Set((s.branches ?? []).filter((b) => b !== s.homeBranch))].sort(),
      branchSets: s.allBranches ? [] : [...new Set(s.branchSets ?? [])].sort(),
      status: s.status ?? 'ACTIVE',
    };
    const { userId: _u, ...proposed } = staff;
    const current = existing ? (({ userId: _x, ...rest }) => rest)(existing) : null;
    return propose(user, 'STAFF', existing ? 'UPDATE' : 'CREATE', { kind: 'STAFF', staff }, { ...proposed }, current, existing ? existing.username : null);
  });

  // ---- territory
  on('GET', '/api/v1/states', () => ok([...db.states].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))));
  on('GET', '/api/v1/pincodes/{pincode}', ({ params }) => {
    if (!PINCODE_PATTERN.test(params.pincode)) throw bad('Pincode must be 6 digits, not starting with 0', [{ field: 'pincode', message: 'Invalid pincode' }]);
    const places = db.pincodes.filter((p) => p.pincode === params.pincode);
    if (places.length === 0) throw notFound(`Pincode ${params.pincode}`);
    return ok(places);
  });
  on('POST', '/api/v1/territory/upload', ({ user, body }) => {
    require(user, P.masterPropose);
    const rows = readCsvUpload(body, ['state', 'district', 'city', 'pincode'], [], 20_000);
    const errors: FieldProblem[] = [];
    const places: PincodePlace[] = [];
    const seen = new Set<string>();
    const findState = (v: string): State | undefined => {
      const x = v.toLowerCase();
      return db.states.find((s) => s.code?.toLowerCase() === x || s.name?.toLowerCase() === x || s.gstStateCode === v);
    };
    rows.forEach((r, i) => {
      const line = i + 2;
      const st = findState(r.state);
      if (!st) errors.push({ field: `row ${line}`, message: `Row ${line}: unknown state "${r.state}"` });
      if (!r.district) errors.push({ field: `row ${line}`, message: `Row ${line}: district is required` });
      if (!r.city) errors.push({ field: `row ${line}`, message: `Row ${line}: city is required` });
      if (!PINCODE_PATTERN.test(r.pincode)) errors.push({ field: `row ${line}`, message: `Row ${line}: pincode "${r.pincode}" must be 6 digits, not starting with 0` });
      const key = `${r.pincode}|${r.city.toLowerCase()}`;
      if (seen.has(key)) errors.push({ field: `row ${line}`, message: `Row ${line}: duplicate ${r.pincode} ${r.city}` });
      seen.add(key);
      if (st) places.push({ pincode: r.pincode, city: r.city, district: r.district, stateCode: st.code, stateName: st.name, gstStateCode: st.gstStateCode });
    });
    if (errors.length) throw rowErrors(errors);
    const pins = new Set(places.map((p) => p.pincode));
    return propose(user, 'TERRITORY', 'UPLOAD', { kind: 'TERRITORY', places }, { rows: places.length, pincodes: pins.size, states: [...new Set(places.map((p) => p.stateCode))].join(', ') });
  });
}
