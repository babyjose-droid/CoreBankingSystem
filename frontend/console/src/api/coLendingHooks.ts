import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from './client';
import type { FileRequest } from './extraHooks';
import type { Approval, CoLendingArrangement, CoLendingArrangementInput, CoLendingDecision, CoLendingDlg, CoLendingDlgCoverInput, CoLendingDlgInvocationInput, CoLendingOffer, CoLendingPartnerClassRow, CoLendingSettlement, CoLendingStatusInput, CoLendingUnsettled } from './types';
import { useApiClient } from './useApiClient';

/** Co-lending as the originating lender (P5-1). */
export function useCoLendingArrangements(enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['co-lending'],
    queryFn: () => unwrap<CoLendingArrangement[]>(api.GET('/api/v1/co-lending/arrangements')),
    enabled,
  });
}

export function useCoLendingArrangement(code: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['co-lending', code],
    queryFn: () => unwrap<CoLendingArrangement>(api.GET('/api/v1/co-lending/arrangements/{code}', { params: { path: { code: code! } } })),
    enabled: !!code,
  });
}

export function useCoLendingOffers(code: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['co-lending', code, 'offers'],
    queryFn: () => unwrap<CoLendingOffer[]>(api.GET('/api/v1/co-lending/arrangements/{code}/offers', { params: { path: { code: code! } } })),
    enabled: !!code,
  });
}

export function useProposeCoLendingArrangement() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: CoLendingArrangementInput) => unwrap<Approval>(api.POST('/api/v1/co-lending/arrangements', { body: a }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export function useProposeCoLendingStatus(code: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (s: CoLendingStatusInput) => unwrap<Approval>(api.POST('/api/v1/co-lending/arrangements/{code}/status', { params: { path: { code } }, body: s }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export function useProposeCoLendingDecisions(code: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (decisions: CoLendingDecision[]) =>
      unwrap<Approval>(api.POST('/api/v1/co-lending/arrangements/{code}/decisions', { params: { path: { code } }, body: { decisions } }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export function useSimulateCoLendingPartner(code: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap<Approval>(api.POST('/api/v1/co-lending/arrangements/{code}/simulate-partner', { params: { path: { code } } }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

/** P5-2: what is due to the partner from the daily splits, and the settlements that paid it. */
export function useCoLendingUnsettled(code: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['co-lending', code, 'unsettled'],
    queryFn: () => unwrap<CoLendingUnsettled>(api.GET('/api/v1/co-lending/arrangements/{code}/unsettled', { params: { path: { code: code! } } })),
    enabled: !!code,
  });
}

export function useCoLendingSettlements(code: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['co-lending', code, 'settlements'],
    queryFn: () => unwrap<CoLendingSettlement[]>(api.GET('/api/v1/co-lending/arrangements/{code}/settlements', { params: { path: { code: code! } } })),
    enabled: !!code,
  });
}

export function useProposeCoLendingSettlement(code: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap<Approval>(api.POST('/api/v1/co-lending/arrangements/{code}/settlements', { params: { path: { code } } }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export const coLendingStatementFile = (id: string, no: string): FileRequest => ({
  request: (api) => api.GET('/api/v1/co-lending/settlements/{id}/statement.csv', { params: { path: { id } }, parseAs: 'blob' }),
  fallbackName: `co-lending-settlement-${no}.csv`,
});

/** P5-3: the default loss guarantee and classification with the partner. */
export function useCoLendingDlg(code: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['co-lending', code, 'dlg'],
    queryFn: () => unwrap<CoLendingDlg>(api.GET('/api/v1/co-lending/arrangements/{code}/dlg', { params: { path: { code: code! } } })),
    enabled: !!code,
  });
}

function useApprovalMutation<T>(fn: (api: ReturnType<typeof useApiClient>, v: T) => Promise<unknown>) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: T) => unwrap<Approval>(fn(api, v) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export const useProposeDlgCover = (code: string) =>
  useApprovalMutation<CoLendingDlgCoverInput>((api, body) => api.POST('/api/v1/co-lending/arrangements/{code}/dlg/covers', { params: { path: { code } }, body }));
export const useReleaseDlgCover = () =>
  useApprovalMutation<{ id: string; reason: string }>((api, v) => api.POST('/api/v1/co-lending/dlg/covers/{id}/release', { params: { path: { id: v.id } }, body: { reason: v.reason } }));
export const useProposeDlgInvocation = (code: string) =>
  useApprovalMutation<CoLendingDlgInvocationInput>((api, body) => api.POST('/api/v1/co-lending/arrangements/{code}/dlg/invocations', { params: { path: { code } }, body }));
export const useProposeDlgRecovery = () =>
  useApprovalMutation<{ id: string; amount: string; reference?: string | null }>((api, v) =>
    api.POST('/api/v1/co-lending/dlg/invocations/{id}/recoveries', { params: { path: { id: v.id } }, body: { amount: v.amount, reference: v.reference ?? null } }));
export const useProposePartnerClasses = (code: string) =>
  useApprovalMutation<CoLendingPartnerClassRow[]>((api, rows) => api.POST('/api/v1/co-lending/arrangements/{code}/partner-classification', { params: { path: { code } }, body: { rows } }));

export const coLendingClassificationFile = (code: string): FileRequest => ({
  request: (api) => api.GET('/api/v1/co-lending/arrangements/{code}/classification.csv', { params: { path: { code } }, parseAs: 'blob' }),
  fallbackName: `co-lending-classification-${code}.csv`,
});

/** The partner's classification file: loan_no and asset_class, optionally npa_since and partner_dpd. */
export function parsePartnerClassCsv(text: string): { rows: CoLendingPartnerClassRow[]; problems: string[] } {
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter((l) => l.trim());
  if (!lines.length) return { rows: [], problems: ['The file is empty'] };
  const head = lines[0].split(',').map((h) => h.trim().toLowerCase());
  const col = (n: string) => head.indexOf(n);
  if (col('loan_no') < 0 || col('asset_class') < 0) return { rows: [], problems: ['The first row names the columns, with at least loan_no and asset_class'] };
  const rows: CoLendingPartnerClassRow[] = [];
  const problems: string[] = [];
  lines.slice(1).forEach((l, i) => {
    const c = l.split(',').map((x) => x.trim());
    const get = (n: string) => (col(n) >= 0 ? c[col(n)] || null : null);
    const cls = (get('asset_class') ?? '').toUpperCase();
    const dpd = get('partner_dpd');
    if (!get('loan_no') || !cls) problems.push(`Row ${i + 2}: a loan number and a class are needed`);
    else if (dpd !== null && !/^\d+$/.test(dpd)) problems.push(`Row ${i + 2}: days past due are a whole number`);
    else rows.push({ loanNo: get('loan_no')!, assetClass: cls as CoLendingPartnerClassRow['assetClass'], npaSince: get('npa_since'), partnerDpd: dpd === null ? null : Number(dpd) });
  });
  if (!rows.length && !problems.length) problems.push('The file has no rows');
  return { rows, problems };
}

export const coLendingOffersFile = (code: string): FileRequest => ({
  request: (api) => api.GET('/api/v1/co-lending/arrangements/{code}/offers.csv', { params: { path: { code } }, parseAs: 'blob' }),
  fallbackName: `co-lending-offers-${code}.csv`,
});

/**
 * The partner's file of decisions: a header row naming loan_no and decision (ACCEPT or DECLINE), optionally
 * partner_loan_ref and reason. Returns the decisions, or the problems with the file.
 */
export function parseDecisionsCsv(text: string): { decisions: CoLendingDecision[]; problems: string[] } {
  const lines = text.replace(/^﻿/, '').split(/\r?\n/).filter((l) => l.trim());
  const problems: string[] = [];
  if (!lines.length) return { decisions: [], problems: ['The file is empty'] };
  const split = (l: string) => {
    const out: string[] = [];
    let cur = '';
    let q = false;
    for (let i = 0; i < l.length; i++) {
      const ch = l[i];
      if (q) {
        if (ch === '"' && l[i + 1] === '"') { cur += '"'; i++; }
        else if (ch === '"') q = false;
        else cur += ch;
      } else if (ch === '"') q = true;
      else if (ch === ',') { out.push(cur); cur = ''; }
      else cur += ch;
    }
    out.push(cur);
    return out.map((x) => x.trim());
  };
  const head = split(lines[0]).map((h) => h.toLowerCase());
  const col = (name: string) => head.indexOf(name);
  if (col('loan_no') < 0 || col('decision') < 0) return { decisions: [], problems: ['The first row names the columns, with at least loan_no and decision'] };
  const decisions: CoLendingDecision[] = [];
  lines.slice(1).forEach((l, i) => {
    const c = split(l);
    const get = (name: string) => (col(name) >= 0 ? c[col(name)] || null : null);
    const decision = (get('decision') ?? '').toUpperCase();
    if (!get('loan_no')) problems.push(`Row ${i + 2}: no loan number`);
    else if (decision !== 'ACCEPT' && decision !== 'DECLINE') problems.push(`Row ${i + 2}: the decision is ACCEPT or DECLINE`);
    else decisions.push({ loanNo: get('loan_no')!, decision, partnerLoanRef: get('partner_loan_ref'), reason: get('reason') });
  });
  if (!decisions.length && !problems.length) problems.push('The file has no decisions');
  return { decisions, problems };
}
