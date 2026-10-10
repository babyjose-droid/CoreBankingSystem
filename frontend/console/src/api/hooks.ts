import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { config } from '../config';
import { useApiClient } from './useApiClient';
import { unwrap, unwrapWithStatus } from './client';
import type {
  Approval,
  ApprovalStatus,
  AuditEvent,
  Branch,
  BulkApproveResult,
  BusinessDay,
  CustomerInput,
  CustomerSummary,
  DedupeMatch,
  EnumValue,
  EodRun,
  EodSchedule,
  GlHead,
  Holiday,
  LedgerEntry,
  Me,
  StatementRow,
  TaxRate,
  EodPendingApprovals,
  Benchmark,
  BenchmarkInput,
  BenchmarkRateInput,
  InterestTable,
  InterestTableInput,
  TrialBalanceRow,
  VerifyResult,
  Voucher,
  VoucherInput,
} from './types';

const live = config.isTest ? false : 30_000;

// ---------- session / platform ----------
export function useMe() {
  const api = useApiClient();
  return useQuery({ queryKey: ['me'], queryFn: () => unwrap<Me>(api.GET('/api/v1/me')), staleTime: 60_000 });
}

export function useBusinessDay() {
  const api = useApiClient();
  return useQuery({
    queryKey: ['business-day'],
    queryFn: () => unwrap<BusinessDay>(api.GET('/api/v1/business-day')),
    refetchInterval: live,
  });
}

export function useBranches() {
  const api = useApiClient();
  return useQuery({ queryKey: ['branches'], queryFn: () => unwrap<Branch[]>(api.GET('/api/v1/branches')), staleTime: 60_000 });
}

export function useHolidays(year: number, branch?: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['holidays', year, branch ?? null],
    queryFn: () => unwrap<Holiday[]>(api.GET('/api/v1/holidays', { params: { query: { year, branch: branch || undefined } } })),
  });
}

export function useTaxRates(asOf?: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['tax-rates', asOf ?? null],
    queryFn: () => unwrap<TaxRate[]>(api.GET('/api/v1/tax-rates', { params: { query: { asOf: asOf || undefined } } })),
  });
}

export function useEnumeration(type: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['enum', type],
    queryFn: () => unwrap<EnumValue[]>(api.GET('/api/v1/enumerations/{type}', { params: { path: { type } } })),
    staleTime: Infinity,
  });
}

// ---------- approvals ----------
export function useApprovals(filter: { status?: ApprovalStatus; entityType?: string } = {}, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['approvals', filter.status ?? null, filter.entityType ?? null],
    queryFn: () =>
      unwrap<Approval[]>(
        api.GET('/api/v1/approvals', { params: { query: { status: filter.status, entityType: filter.entityType || undefined } } }) as never,
      ),
    enabled,
    refetchInterval: live,
  });
}

export function useApproval(id: string | null) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['approval', id],
    queryFn: () => unwrap<Approval>(api.GET('/api/v1/approvals/{id}', { params: { path: { id: id! } } }) as never),
    enabled: !!id,
  });
}

/** Any approval decision can change almost anything (masters, ledger, business data): refresh everything. */
function useInvalidateAll() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries();
}

export function useApprove() {
  const api = useApiClient();
  const invalidate = useInvalidateAll();
  return useMutation({
    mutationFn: ({ id, note }: { id: string; note?: string }) =>
      unwrap<Approval>(api.POST('/api/v1/approvals/{id}/approve', { params: { path: { id } }, body: note ? { note } : {} }) as never),
    onSuccess: invalidate,
  });
}

export function useReject() {
  const api = useApiClient();
  const invalidate = useInvalidateAll();
  return useMutation({
    mutationFn: ({ id, note }: { id: string; note: string }) =>
      unwrap<Approval>(api.POST('/api/v1/approvals/{id}/reject', { params: { path: { id } }, body: { note } }) as never),
    onSuccess: invalidate,
  });
}

export function useBulkApprove() {
  const api = useApiClient();
  const invalidate = useInvalidateAll();
  return useMutation({
    mutationFn: ({ ids, note }: { ids: string[]; note?: string }) =>
      unwrap<BulkApproveResult[]>(api.POST('/api/v1/approvals/bulk-approve', { body: { ids, note } })),
    onSuccess: invalidate,
  });
}

/** After a proposal, the pending queue (and header badge) changes. */
function useAfterProposal() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ['approvals'] });
  };
}

// ---------- masters ----------
export function useProposeBranch() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (b: Branch) => unwrap<Approval>(api.POST('/api/v1/branches', { body: b }) as never),
    onSuccess: after,
  });
}

export function useProposeHolidays() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (h: Holiday[]) => unwrap<Approval>(api.POST('/api/v1/holidays', { body: h }) as never),
    onSuccess: after,
  });
}

export function useProposeTaxRate() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (t: TaxRate) => unwrap<Approval>(api.POST('/api/v1/tax-rates', { body: t }) as never),
    onSuccess: after,
  });
}

// ---------- interest tables ----------
export function useInterestTables(enabled = true) {
  const api = useApiClient();
  return useQuery({ enabled, queryKey: ['interest-tables'], queryFn: () => unwrap<InterestTable[]>(api.GET('/api/v1/interest-tables')) });
}

export function useProposeInterestTable() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (t: InterestTableInput) => unwrap<Approval>(api.POST('/api/v1/interest-tables', { body: t }) as never),
    onSuccess: after,
  });
}

// ---------- benchmark rates ----------
export function useBenchmarks() {
  const api = useApiClient();
  return useQuery({ queryKey: ['benchmarks'], queryFn: () => unwrap<Benchmark[]>(api.GET('/api/v1/benchmarks')) });
}

export function useProposeBenchmark() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (b: BenchmarkInput) => unwrap<Approval>(api.POST('/api/v1/benchmarks', { body: b }) as never),
    onSuccess: after,
  });
}

export function useProposeBenchmarkRate() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: ({ code, ...body }: BenchmarkRateInput & { code: string }) =>
      unwrap<Approval>(api.POST('/api/v1/benchmarks/{code}/rates', { params: { path: { code } }, body }) as never),
    onSuccess: after,
  });
}

// ---------- customers ----------
export function useCustomers(q: string, page = 0, size = 20, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['customers', q, page, size],
    queryFn: () => unwrap<CustomerSummary[]>(api.GET('/api/v1/customers', { params: { query: { q: q || undefined, page, size } } })),
    enabled,
  });
}

export function useCustomer(id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['customer', id],
    queryFn: () => unwrap<CustomerSummary>(api.GET('/api/v1/customers/{id}', { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}

export function useDedupeCheck() {
  const api = useApiClient();
  return useMutation({
    mutationFn: (input: CustomerInput) => unwrap<DedupeMatch[]>(api.POST('/api/v1/customers/dedupe-check', { body: input })),
  });
}

export type CreateCustomerResult =
  | { kind: 'existing'; customer: CustomerSummary }
  | { kind: 'pending'; approval: Approval };

export function useCreateCustomer() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: async (input: CustomerInput): Promise<CreateCustomerResult> => {
      const { data, status } = await unwrapWithStatus<CustomerSummary | Approval>(api.POST('/api/v1/customers', { body: input }) as never);
      return status === 200 ? { kind: 'existing', customer: data as CustomerSummary } : { kind: 'pending', approval: data as Approval };
    },
    onSuccess: after,
  });
}

// ---------- ledger ----------
export function useGlHeads(enabled = true) {
  const api = useApiClient();
  return useQuery({ queryKey: ['gl-heads'], queryFn: () => unwrap<GlHead[]>(api.GET('/api/v1/gl/heads')), staleTime: 60_000, enabled });
}

export function useProposeGlHead() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (h: GlHead) => unwrap<Approval>(api.POST('/api/v1/gl/heads', { body: h }) as never),
    onSuccess: after,
  });
}

export function useVouchers(from?: string, to?: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['vouchers', from ?? null, to ?? null],
    queryFn: () => unwrap<Voucher[]>(api.GET('/api/v1/gl/vouchers', { params: { query: { from: from || undefined, to: to || undefined } } })),
  });
}

export function useProposeVoucher() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (v: VoucherInput) => unwrap<Approval>(api.POST('/api/v1/gl/vouchers', { body: v }) as never),
    onSuccess: after,
  });
}

export function useProposeReversal() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: ({ id, note }: { id: string; note: string }) =>
      unwrap<Approval>(api.POST('/api/v1/gl/vouchers/{id}/reverse', { params: { path: { id } }, body: { note } }) as never),
    onSuccess: after,
  });
}

export function useTrialBalance(asOf: string, branch?: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['trial-balance', asOf, branch ?? null],
    queryFn: () => unwrap<TrialBalanceRow[]>(api.GET('/api/v1/gl/trial-balance', { params: { query: { asOf, branch: branch || undefined } } })),
    enabled: !!asOf,
  });
}

export function useGlEntries(q: { glCode: string; from: string; to: string; branch?: string } | null) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['gl-entries', q],
    queryFn: () =>
      unwrap<LedgerEntry[]>(
        api.GET('/api/v1/gl/entries', { params: { query: { glCode: q!.glCode, from: q!.from, to: q!.to, branch: q!.branch || undefined } } }),
      ),
    enabled: !!q,
  });
}

export function useProfitAndLoss(from: string, to: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['pnl', from, to],
    queryFn: () => unwrap<StatementRow[]>(api.GET('/api/v1/gl/profit-and-loss', { params: { query: { from, to } } })),
    enabled: !!from && !!to,
  });
}

export function useBalanceSheet(asOf: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['balance-sheet', asOf],
    queryFn: () => unwrap<StatementRow[]>(api.GET('/api/v1/gl/balance-sheet', { params: { query: { asOf } } })),
    enabled: !!asOf,
  });
}

// ---------- EOD ----------
export function useEodRuns(enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['eod-runs'],
    queryFn: () => unwrap<EodRun[]>(api.GET('/api/v1/eod/runs')),
    enabled,
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'RUNNING') ? 1000 : false),
  });
}

export const EOD_POLL_MS = 1000;

export function useEodRun(runId: number | null) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useQuery({
    queryKey: ['eod-run', runId],
    queryFn: async () => {
      const run = await unwrap<EodRun>(api.GET('/api/v1/eod/runs/{runId}', { params: { path: { runId: runId! } } }));
      if (run.status !== 'RUNNING') {
        // The business date may have moved: refresh header/dashboard data.
        void qc.invalidateQueries({ queryKey: ['business-day'] });
        void qc.invalidateQueries({ queryKey: ['me'] });
        void qc.invalidateQueries({ queryKey: ['eod-runs'] });
      }
      return run;
    },
    enabled: runId !== null && !Number.isNaN(runId),
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' ? EOD_POLL_MS : false),
  });
}

/** Dated approvals still pending, shown before a run starts. */
export function useEodPendingApprovals(enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['eod-pending-approvals'],
    queryFn: () => unwrap<EodPendingApprovals>(api.GET('/api/v1/eod/pending-approvals')),
    enabled,
  });
}

export function useStartEod() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap<EodRun>(api.POST('/api/v1/eod/runs')),
    onSuccess: (run) => {
      qc.setQueryData(['eod-run', run.id], run);
      void qc.invalidateQueries({ queryKey: ['eod-runs'] });
      void qc.invalidateQueries({ queryKey: ['business-day'] });
    },
  });
}

export function useRestartEod() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: number) => unwrap<EodRun>(api.POST('/api/v1/eod/runs/{runId}/restart', { params: { path: { runId } } })),
    onSuccess: (run) => {
      qc.setQueryData(['eod-run', run.id], run);
      void qc.invalidateQueries({ queryKey: ['eod-runs'] });
      void qc.invalidateQueries({ queryKey: ['business-day'] });
    },
  });
}

export function useEodSchedule() {
  const api = useApiClient();
  return useQuery({ queryKey: ['eod-schedule'], queryFn: () => unwrap<EodSchedule>(api.GET('/api/v1/eod/schedule')) });
}

export function useProposeEodSchedule() {
  const api = useApiClient();
  const after = useAfterProposal();
  return useMutation({
    mutationFn: (s: EodSchedule) => unwrap<Approval>(api.PUT('/api/v1/eod/schedule', { body: s }) as never),
    onSuccess: after,
  });
}

// ---------- audit ----------
export function useAuditEvents(f: { entityType?: string; entityId?: string; limit?: number }, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['audit', f.entityType ?? null, f.entityId ?? null, f.limit ?? 100],
    queryFn: () =>
      unwrap<AuditEvent[]>(
        api.GET('/api/v1/audit/events', {
          params: { query: { entityType: f.entityType || undefined, entityId: f.entityId || undefined, limit: f.limit ?? 100 } },
        }),
      ),
    enabled,
  });
}

export function useVerifyAudit() {
  const api = useApiClient();
  return useMutation({ mutationFn: () => unwrap<VerifyResult>(api.GET('/api/v1/audit/verify') as never) });
}
