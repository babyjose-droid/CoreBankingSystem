import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from './useApiClient';
import { unwrap, unwrapWithStatus } from './client';
import type {
  DeferredReceipt,
  DisbursementSimulation,
  LoanProductTemplate,
  LoanTranches,
  NpaOverrideRequest,
  SanctionChangePreview,
  SanctionChangeRequest,
  TransactionSimulation,
  TransactionSimulationRequest,
  AmendmentPreview,
  AmendmentRequest,
  Approval,
  CancellationQuote,
  Loan,
  LoanApplication,
  LoanKfs,
  LoanProduct,
  LoanSchedule,
  LoanStatus,
  LoanSummary,
  LoanAmendment,
  LoanTxn,
  Money,
  PreclosureQuote,
  RestructureSimulation,
  RestructureTerms,
} from './types';

// ---------- products ----------
export function useLoanProducts(enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan-products'],
    queryFn: () => unwrap<LoanProduct[]>(api.GET('/api/v1/loan-products')),
    staleTime: 60_000,
    enabled,
  });
}

export function useLoanProduct(code: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan-product', code],
    queryFn: () => unwrap<LoanProduct>(api.GET('/api/v1/loan-products/{code}', { params: { path: { code: code! } } })),
    enabled: !!code,
  });
}

export function useProposeLoanProduct() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: LoanProduct) => unwrap<Approval>(api.POST('/api/v1/loan-products', { body: p }) as never),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

// ---------- loans ----------
export function useLoans(f: { q: string; status?: LoanStatus | ''; page: number; size: number }) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loans', f.q, f.status ?? '', f.page, f.size],
    queryFn: () =>
      unwrap<LoanSummary[]>(
        api.GET('/api/v1/loans', { params: { query: { q: f.q || undefined, status: f.status || undefined, page: f.page, size: f.size } } }),
      ),
  });
}

export function useLoan(id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id],
    queryFn: () => unwrap<Loan>(api.GET('/api/v1/loans/{id}', { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}

export function useLoanSchedule(id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id, 'schedule'],
    queryFn: () => unwrap<LoanSchedule>(api.GET('/api/v1/loans/{id}/schedule', { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}

/** `dayEnd`: also the day-end entries (type EOD), which the list leaves out by default. */
export function useLoanTransactions(id: string | undefined, dayEnd = false) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id, 'transactions', { dayEnd }],
    queryFn: () => unwrap<LoanTxn[]>(api.GET('/api/v1/loans/{id}/transactions', { params: { path: { id: id! }, query: dayEnd ? { dayEnd: true } : {} } })),
    enabled: !!id,
  });
}

export function useLoanKfs(id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id, 'kfs'],
    queryFn: () => unwrap<LoanKfs>(api.GET('/api/v1/loans/{id}/kfs', { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}

/** Quotes are "as of now": always refetched when the dialog opens. */
export function usePreclosureQuote(id: string, enabled: boolean) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id, 'preclosure-quote'],
    queryFn: () => unwrap<PreclosureQuote>(api.GET('/api/v1/loans/{id}/preclosure-quote', { params: { path: { id } } })),
    enabled,
    staleTime: 0,
    gcTime: 0,
  });
}

export function useCancellationQuote(id: string, enabled: boolean) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id, 'cancellation-quote'],
    queryFn: () => unwrap<CancellationQuote>(api.GET('/api/v1/loans/{id}/cancellation-quote', { params: { path: { id } } })),
    enabled,
    staleTime: 0,
    gcTime: 0,
  });
}

export function usePreviewLoan() {
  const api = useApiClient();
  return useMutation({ mutationFn: (a: LoanApplication) => unwrap<LoanKfs>(api.POST('/api/v1/loans/preview', { body: a })) });
}

export function useCreateLoan() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: LoanApplication) => unwrap<Loan>(api.POST('/api/v1/loans', { body: a })),
    onSuccess: (loan) => {
      qc.setQueryData(['loan', loan.id], loan);
      void qc.invalidateQueries({ queryKey: ['loans'] });
    },
  });
}

/** After any loan transaction: the loan, its schedule, transactions and the list all change. */
function useAfterLoanChange() {
  const qc = useQueryClient();
  return (loan: Loan) => {
    qc.setQueryData(['loan', loan.id], loan);
    void qc.invalidateQueries({ queryKey: ['loan', loan.id] });
    void qc.invalidateQueries({ queryKey: ['loans'] });
  };
}

function useAfterLoanProposal() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ['approvals'] });
}

export function useAcceptKfs(id: string) {
  const api = useApiClient();
  const after = useAfterLoanChange();
  return useMutation({
    mutationFn: (b: { channel?: string; evidenceRef?: string }) =>
      unwrap<Loan>(api.POST('/api/v1/loans/{id}/kfs-acceptance', { params: { path: { id } }, body: b })),
    onSuccess: after,
  });
}

export type DisburseResult = { kind: 'disbursed'; loan: Loan } | { kind: 'pending'; approval: Approval };

export function useDisburseLoan(id: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (b: { amount?: Money; beneficiaryName?: string; beneficiaryAccount?: string; ifsc?: string; mode?: string }): Promise<DisburseResult> => {
      const { data, status } = await unwrapWithStatus<Loan | Approval>(api.POST('/api/v1/loans/{id}/disbursement', { params: { path: { id } }, body: b }) as never);
      return status === 202 ? { kind: 'pending', approval: data as Approval } : { kind: 'disbursed', loan: data as Loan };
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['approvals'] });
      void qc.invalidateQueries({ queryKey: ['loan', id] });
    },
  });
}

export type RepayResult = { kind: 'posted'; loan: Loan } | { kind: 'deferred'; receipt: DeferredReceipt };

export function useRepayLoan(id: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  const after = useAfterLoanChange();
  return useMutation({
    // 200 with the loan, or 202 with a deferred receipt when the receipt arrives after the end-of-day cut-off.
    mutationFn: async (b: { amount: Money; valueDate?: string; mode?: string; reference?: string }): Promise<RepayResult> => {
      const { data, status } = await unwrapWithStatus<Loan | { receipt?: DeferredReceipt }>(api.POST('/api/v1/loans/{id}/repayments', { params: { path: { id } }, body: b }) as never);
      return status === 202 ? { kind: 'deferred', receipt: (data as { receipt?: DeferredReceipt }).receipt ?? {} } : { kind: 'posted', loan: data as Loan };
    },
    onSuccess: (r) => {
      if (r.kind === 'posted') after(r.loan);
      else void qc.invalidateQueries({ queryKey: ['deferred-receipts'] });
    },
  });
}

export function usePrepayLoan(id: string) {
  const api = useApiClient();
  const after = useAfterLoanChange();
  return useMutation({
    mutationFn: (b: { amount: Money; mode?: 'REDUCE_EMI' | 'REDUCE_TENURE' }) =>
      unwrap<Loan>(api.POST('/api/v1/loans/{id}/prepayments', { params: { path: { id } }, body: b })),
    onSuccess: after,
  });
}

export function usePrecloseLoan(id: string) {
  const api = useApiClient();
  const after = useAfterLoanChange();
  return useMutation({
    mutationFn: (amount: Money) => unwrap<Loan>(api.POST('/api/v1/loans/{id}/preclosure', { params: { path: { id } }, body: { amount } })),
    onSuccess: after,
  });
}

export function useCancelLoan(id: string) {
  const api = useApiClient();
  const after = useAfterLoanChange();
  return useMutation({
    mutationFn: (amount: Money) => unwrap<Loan>(api.POST('/api/v1/loans/{id}/cancellation', { params: { path: { id } }, body: { amount } })),
    onSuccess: after,
  });
}

export function useChargeLoanFee(id: string) {
  const api = useApiClient();
  const after = useAfterLoanChange();
  return useMutation({
    mutationFn: (b: { feeCode: string; base?: Money }) => unwrap<Loan>(api.POST('/api/v1/loans/{id}/charges', { params: { path: { id } }, body: b })),
    onSuccess: after,
  });
}

export function useWaiveLoanCharge(id: string) {
  const api = useApiClient();
  const after = useAfterLoanProposal();
  return useMutation({
    mutationFn: ({ chargeId, amount, reason }: { chargeId: string; amount: Money; reason: string }) =>
      unwrap<Approval>(api.POST('/api/v1/loans/{id}/charges/{chargeId}/waiver', { params: { path: { id, chargeId } }, body: { amount, reason } }) as never),
    onSuccess: after,
  });
}

export function useReverseLoanTxn(id: string) {
  const api = useApiClient();
  const after = useAfterLoanProposal();
  return useMutation({
    mutationFn: ({ txnId, reason }: { txnId: string; reason: string }) =>
      unwrap<Approval>(api.POST('/api/v1/loans/{id}/transactions/{txnId}/reverse', { params: { path: { id, txnId } }, body: { reason } }) as never),
    onSuccess: after,
  });
}

export function useFreezeLoan(id: string) {
  const api = useApiClient();
  const after = useAfterLoanChange();
  return useMutation({
    mutationFn: ({ freeze, reason }: { freeze: boolean; reason: string }) =>
      unwrap<Loan>(
        freeze
          ? api.POST('/api/v1/loans/{id}/freeze', { params: { path: { id } }, body: { reason } })
          : api.POST('/api/v1/loans/{id}/unfreeze', { params: { path: { id } }, body: { reason } }),
      ),
    onSuccess: after,
  });
}

// ---------- amendments and restructure ----------
export function useLoanAmendments(id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id, 'amendments'],
    queryFn: () => unwrap<LoanAmendment[]>(api.GET('/api/v1/loans/{id}/amendments', { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}

export function usePreviewAmendment(id: string) {
  const api = useApiClient();
  return useMutation({
    mutationFn: (req: AmendmentRequest) => unwrap<AmendmentPreview>(api.POST('/api/v1/loans/{id}/amendments/preview', { params: { path: { id } }, body: req })),
  });
}

export function useProposeAmendment(id: string) {
  const api = useApiClient();
  const after = useAfterLoanProposal();
  return useMutation({
    mutationFn: (req: AmendmentRequest) => unwrap<Approval>(api.POST('/api/v1/loans/{id}/amendments', { params: { path: { id } }, body: req }) as never),
    onSuccess: after,
  });
}

export function useSimulateRestructure(id: string) {
  const api = useApiClient();
  return useMutation({
    mutationFn: (options: RestructureTerms[]) =>
      unwrap<RestructureSimulation>(api.POST('/api/v1/loans/{id}/restructure/simulation', { params: { path: { id } }, body: { options } })),
  });
}

export function useProposeRestructure(id: string) {
  const api = useApiClient();
  const after = useAfterLoanProposal();
  return useMutation({
    mutationFn: (terms: RestructureTerms) => unwrap<Approval>(api.POST('/api/v1/loans/{id}/restructure', { params: { path: { id } }, body: terms }) as never),
    onSuccess: after,
  });
}

// ---------- lending completion: templates, product preview, tranches, simulations, sanction change, NPA override ----------
export function useLoanProductTemplates(enabled = true) {
  const api = useApiClient();
  return useQuery({ queryKey: ['loan-product-templates'], queryFn: () => unwrap<LoanProductTemplate[]>(api.GET('/api/v1/loan-product-templates')), staleTime: Infinity, enabled });
}

export type ProductPreview = LoanKfs & { sampleSchedule?: boolean };
export function usePreviewLoanProduct() {
  const api = useApiClient();
  return useMutation({
    mutationFn: (b: { product: Record<string, unknown>; amount?: string | null; tenorMonths?: number | null; rate?: string | null }) =>
      unwrap<ProductPreview>(api.POST('/api/v1/loan-products/preview', { body: b as never })),
  });
}

export function useLoanTranches(id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['loan', id, 'tranches'],
    queryFn: () => unwrap<LoanTranches>(api.GET('/api/v1/loans/{id}/tranches', { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}

export function useSimulateDisbursement(id: string) {
  const api = useApiClient();
  return useMutation({
    mutationFn: (amount: Money | null) => unwrap<DisbursementSimulation>(api.POST('/api/v1/loans/{id}/simulations/disbursement', { params: { path: { id } }, body: { amount } })),
  });
}

export function useSimulateTransaction(id: string) {
  const api = useApiClient();
  return useMutation({
    mutationFn: (req: TransactionSimulationRequest) => unwrap<TransactionSimulation>(api.POST('/api/v1/loans/{id}/simulations/transaction', { params: { path: { id } }, body: req })),
  });
}

export function usePreviewSanctionChange(id: string) {
  const api = useApiClient();
  return useMutation({
    mutationFn: (req: SanctionChangeRequest) => unwrap<SanctionChangePreview>(api.POST('/api/v1/loans/{id}/sanction-change/preview', { params: { path: { id } }, body: req })),
  });
}

export function useProposeSanctionChange(id: string) {
  const api = useApiClient();
  const after = useAfterLoanProposal();
  return useMutation({
    mutationFn: (req: SanctionChangeRequest) => unwrap<Approval>(api.POST('/api/v1/loans/{id}/sanction-change', { params: { path: { id } }, body: req }) as never),
    onSuccess: after,
  });
}

export function useProposeNpaOverride(id: string) {
  const api = useApiClient();
  const after = useAfterLoanProposal();
  return useMutation({
    mutationFn: (req: NpaOverrideRequest) => unwrap<Approval>(api.POST('/api/v1/loans/{id}/npa-override', { params: { path: { id } }, body: req }) as never),
    onSuccess: after,
  });
}

export function useProposeNpaRelease(id: string) {
  const api = useApiClient();
  const after = useAfterLoanProposal();
  return useMutation({
    mutationFn: (reason: string) => unwrap<Approval>(api.POST('/api/v1/loans/{id}/npa-override/release', { params: { path: { id } }, body: { reason } }) as never),
    onSuccess: after,
  });
}
