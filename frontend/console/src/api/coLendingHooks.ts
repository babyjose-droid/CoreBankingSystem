import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from './client';
import type { FileRequest } from './extraHooks';
import type { Approval, CoLendingArrangement, CoLendingArrangementInput, CoLendingDecision, CoLendingOffer, CoLendingStatusInput } from './types';
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
