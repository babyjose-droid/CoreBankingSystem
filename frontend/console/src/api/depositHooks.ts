import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from './client';
import type { CollectionOrder } from './integrationTypes';
import type {
  Approval,
  CasaAccountInput,
  DepositAccount,
  DepositHold,
  DepositHoldInput,
  DepositMovement,
  DepositTransactionInput,
  DepositTransferInput,
  TermDepositFundingInput,
  DepositAccountSummary,
  DepositCollectionOrderInput,
  DepositFamily,
  DepositProduct,
  DepositProductInput,
  DepositProductVersion,
  DepositRuleSet,
  TermDepositInput,
  TermDepositPreview,
} from './types';
import { useApiClient } from './useApiClient';

/** The deposit rule set in force. `family` picks the path, so a tenant with only one of the modules can read it. */
export function useDepositRules(family: DepositFamily, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-rules'],
    queryFn: () => unwrap<DepositRuleSet>(family === 'CASA' ? api.GET('/api/v1/casa/rules') : api.GET('/api/v1/deposits/rules')),
    staleTime: 60_000,
    enabled,
  });
}

export function useDepositProducts(family: DepositFamily, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-products', family],
    queryFn: () => unwrap<DepositProduct[]>(family === 'CASA' ? api.GET('/api/v1/casa/products') : api.GET('/api/v1/deposits/products')),
    enabled,
  });
}

export function useDepositProduct(family: DepositFamily, code: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-product', family, code],
    queryFn: () =>
      unwrap<DepositProduct>(
        family === 'CASA'
          ? api.GET('/api/v1/casa/products/{code}', { params: { path: { code: code! } } })
          : api.GET('/api/v1/deposits/products/{code}', { params: { path: { code: code! } } }),
      ),
    enabled: !!code,
  });
}

export function useDepositProductHistory(family: DepositFamily, code: string | undefined, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-product-history', family, code],
    queryFn: () =>
      unwrap<DepositProductVersion[]>(
        family === 'CASA'
          ? api.GET('/api/v1/casa/products/{code}/history', { params: { path: { code: code! } } })
          : api.GET('/api/v1/deposits/products/{code}/history', { params: { path: { code: code! } } }),
      ),
    enabled: !!code && enabled,
  });
}

/** What stops the product as entered from being approved; nothing is stored. */
export function useCheckDepositProduct(family: DepositFamily) {
  const api = useApiClient();
  return useMutation({
    mutationFn: (body: DepositProductInput) =>
      unwrap<{ problems?: string[] }>(family === 'CASA' ? api.POST('/api/v1/casa/products/check', { body }) : api.POST('/api/v1/deposits/products/check', { body })),
  });
}

export function useProposeDepositProduct(family: DepositFamily) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: DepositProductInput) =>
      unwrap<Approval>((family === 'CASA' ? api.POST('/api/v1/casa/products', { body }) : api.POST('/api/v1/deposits/products', { body })) as never),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export function useWithdrawDepositProduct(family: DepositFamily, code: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (reason: string) =>
      unwrap<Approval>(
        (family === 'CASA'
          ? api.POST('/api/v1/casa/products/{code}/withdraw', { params: { path: { code } }, body: { reason } })
          : api.POST('/api/v1/deposits/products/{code}/withdraw', { params: { path: { code } }, body: { reason } })) as never,
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

export function useDepositAccounts(family: DepositFamily, filter: { customerId?: string; status?: string } = {}, enabled = true) {
  const api = useApiClient();
  const query = { customerId: filter.customerId || undefined, status: (filter.status || undefined) as DepositAccountSummary['status'] };
  return useQuery({
    queryKey: ['deposit-accounts', family, filter.customerId ?? null, filter.status ?? null],
    queryFn: () =>
      unwrap<DepositAccountSummary[]>(
        family === 'CASA' ? api.GET('/api/v1/casa/accounts', { params: { query } }) : api.GET('/api/v1/deposits/accounts', { params: { query } }),
      ),
    enabled,
  });
}

export function useDepositAccount(family: DepositFamily, id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-account', family, id],
    queryFn: () =>
      unwrap<DepositAccount>(
        family === 'CASA'
          ? api.GET('/api/v1/casa/accounts/{id}', { params: { path: { id: id! } } })
          : api.GET('/api/v1/deposits/accounts/{id}', { params: { path: { id: id! } } }),
      ),
    enabled: !!id,
  });
}

export function useOpenCasaAccount() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CasaAccountInput) => unwrap<Approval>(api.POST('/api/v1/casa/accounts', { body }) as never),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

/** The figures of a deposit before it is booked: the same calculation as the booking. */
export function usePreviewTermDeposit() {
  const api = useApiClient();
  return useMutation({
    mutationFn: (body: TermDepositInput) => unwrap<TermDepositPreview>(api.POST('/api/v1/deposits/preview', { body })),
  });
}

export function useBookTermDeposit() {
  const api = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: TermDepositInput) => unwrap<Approval>(api.POST('/api/v1/deposits/accounts', { body }) as never),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

// ---- money on deposit accounts (P4-2) ---------------------------------------------------------------------------

/** After money moves (or a request is raised): the accounts, their movements and holds, and the approval queue. */
function useMoneyInvalidation() {
  const qc = useQueryClient();
  return () => {
    for (const key of ['deposit-account', 'deposit-accounts', 'deposit-movements', 'deposit-holds', 'approvals']) qc.invalidateQueries({ queryKey: [key] });
  };
}

export function useDepositMovements(family: DepositFamily, id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-movements', family, id],
    queryFn: () =>
      unwrap<DepositMovement[]>(
        family === 'CASA'
          ? api.GET('/api/v1/casa/accounts/{id}/movements', { params: { path: { id: id! } } })
          : api.GET('/api/v1/deposits/accounts/{id}/movements', { params: { path: { id: id! } } }),
      ),
    enabled: !!id,
  });
}

export function useDepositHolds(family: DepositFamily, id: string | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-holds', family, id],
    queryFn: () =>
      unwrap<DepositHold[]>(
        family === 'CASA'
          ? api.GET('/api/v1/casa/accounts/{id}/holds', { params: { path: { id: id! } } })
          : api.GET('/api/v1/deposits/accounts/{id}/holds', { params: { path: { id: id! } } }),
      ),
    enabled: !!id,
  });
}

export function useCasaTransaction(id: string) {
  const api = useApiClient();
  const done = useMoneyInvalidation();
  return useMutation({
    mutationFn: (body: DepositTransactionInput) => unwrap<Approval>(api.POST('/api/v1/casa/accounts/{id}/transactions', { params: { path: { id } }, body }) as never),
    onSuccess: done,
  });
}

export function useCasaTransfer() {
  const api = useApiClient();
  const done = useMoneyInvalidation();
  return useMutation({
    mutationFn: (body: DepositTransferInput) => unwrap<Approval>(api.POST('/api/v1/casa/transfers', { body }) as never),
    onSuccess: done,
  });
}

export function useReverseMovement() {
  const api = useApiClient();
  const done = useMoneyInvalidation();
  return useMutation({
    mutationFn: ({ movementId, reason }: { movementId: string; reason: string }) =>
      unwrap<Approval>(api.POST('/api/v1/casa/movements/{movementId}/reverse', { params: { path: { movementId } }, body: { reason } }) as never),
    onSuccess: done,
  });
}

export function usePlaceHold(family: DepositFamily, id: string) {
  const api = useApiClient();
  const done = useMoneyInvalidation();
  return useMutation({
    mutationFn: (body: DepositHoldInput) =>
      unwrap<Approval>(
        (family === 'CASA'
          ? api.POST('/api/v1/casa/accounts/{id}/holds', { params: { path: { id } }, body })
          : api.POST('/api/v1/deposits/accounts/{id}/holds', { params: { path: { id } }, body })) as never,
      ),
    onSuccess: done,
  });
}

export function useReleaseHold(family: DepositFamily) {
  const api = useApiClient();
  const done = useMoneyInvalidation();
  return useMutation({
    mutationFn: ({ holdId, reason }: { holdId: string; reason: string }) =>
      unwrap<Approval>(
        (family === 'CASA'
          ? api.POST('/api/v1/casa/holds/{holdId}/release', { params: { path: { holdId } }, body: { reason } })
          : api.POST('/api/v1/deposits/holds/{holdId}/release', { params: { path: { holdId } }, body: { reason } })) as never,
      ),
    onSuccess: done,
  });
}

export function useFundTermDeposit(id: string) {
  const api = useApiClient();
  const done = useMoneyInvalidation();
  return useMutation({
    mutationFn: (body: TermDepositFundingInput) => unwrap<Approval>(api.POST('/api/v1/deposits/accounts/{id}/fund', { params: { path: { id } }, body }) as never),
    onSuccess: done,
  });
}

export function useReverseFunding(id: string) {
  const api = useApiClient();
  const done = useMoneyInvalidation();
  return useMutation({
    mutationFn: (reason: string) => unwrap<Approval>(api.POST('/api/v1/deposits/accounts/{id}/funding/reverse', { params: { path: { id } }, body: { reason } }) as never),
    onSuccess: done,
  });
}

// ---- payment links for deposit accounts (P4-2b) ----------------------------------------------------------------

export function useDepositCollectionOrders(family: DepositFamily, id: string | undefined, enabled = true) {
  const api = useApiClient();
  return useQuery({
    queryKey: ['deposit-orders', family, id],
    queryFn: () =>
      unwrap<CollectionOrder[]>(
        (family === 'CASA'
          ? api.GET('/api/v1/casa/accounts/{id}/collection-orders', { params: { path: { id: id! } } })
          : api.GET('/api/v1/deposits/accounts/{id}/collection-orders', { params: { path: { id: id! } } })) as never,
      ),
    enabled: !!id && enabled,
  });
}

export function useCreateDepositCollectionOrder(family: DepositFamily, id: string) {
  const api = useApiClient();
  const qc = useQueryClient();
  const done = useMoneyInvalidation();
  return useMutation({
    mutationFn: (body: DepositCollectionOrderInput) =>
      unwrap<CollectionOrder>(
        (family === 'CASA'
          ? api.POST('/api/v1/casa/accounts/{id}/collection-orders', { params: { path: { id } }, body })
          : api.POST('/api/v1/deposits/accounts/{id}/collection-orders', { params: { path: { id } }, body })) as never,
      ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['deposit-orders'] });
      done();
    },
  });
}
